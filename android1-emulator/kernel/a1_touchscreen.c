/*
 * Bridge QEMU's pointer devices to a real touchscreen for Android 1.6.
 *
 * Donut's EventHub only classifies a device as a touchscreen when it reports
 * BTN_TOUCH plus ABS_X/ABS_Y, and only as a trackball when it reports
 * BTN_MOUSE plus REL_X/REL_Y.  QEMU's usb-tablet reports BTN_LEFT with
 * absolute axes and matches neither, so Android ignores it.
 *
 * QEMU also splits a synthetic click across devices: absolute coordinates go
 * to the usb-tablet, but BTN_LEFT goes to the first pointer handler, which is
 * the PL050 PS/2 mouse.  So listen to both and keep the last absolute
 * position; a left click from either device becomes a touch there.  Relative
 * motion (VNC clients without absolute pointer support) is integrated into
 * the position as a fallback.
 */
#include <linux/init.h>
#include <linux/input.h>
#include <linux/slab.h>
#include <linux/bitops.h>
#include <linux/miscdevice.h>
#include <linux/fs.h>
#include <linux/uaccess.h>

#define A1TS_MAX	32767
/* VNC relative motion arrives in display pixels; convert to the abs range. */
#define A1TS_REL_SCALE	64

static struct input_dev *a1ts_dev;
static int a1ts_x = A1TS_MAX / 2;
static int a1ts_y = A1TS_MAX / 2;
static bool a1ts_down;

static int a1ts_clamp(int v)
{
	if (v < 0)
		return 0;
	if (v > A1TS_MAX)
		return A1TS_MAX;
	return v;
}

static void a1ts_move(unsigned int code, int value)
{
	if (code == ABS_X)
		a1ts_x = value;
	else
		a1ts_y = value;

	if (a1ts_down) {
		input_event(a1ts_dev, EV_ABS, code, value);
		input_sync(a1ts_dev);
	}
}

static void a1ts_event(struct input_handle *handle, unsigned int type,
		       unsigned int code, int value)
{
	if (type == EV_ABS) {
		if (code == ABS_X || code == ABS_Y)
			a1ts_move(code, value);
		return;
	}

	if (type == EV_REL) {
		if (code == REL_X)
			a1ts_x = a1ts_clamp(a1ts_x + value * A1TS_REL_SCALE);
		else if (code == REL_Y)
			a1ts_y = a1ts_clamp(a1ts_y + value * A1TS_REL_SCALE);
		return;
	}

	if (type != EV_KEY || code != BTN_LEFT)
		return;

	if (value && !a1ts_down) {
		input_event(a1ts_dev, EV_ABS, ABS_X, a1ts_x);
		input_event(a1ts_dev, EV_ABS, ABS_Y, a1ts_y);
		input_event(a1ts_dev, EV_KEY, BTN_TOUCH, 1);
		input_sync(a1ts_dev);
		a1ts_down = true;
	} else if (!value && a1ts_down) {
		input_event(a1ts_dev, EV_KEY, BTN_TOUCH, 0);
		input_sync(a1ts_dev);
		a1ts_down = false;
	}
}

static int a1ts_connect(struct input_handler *handler, struct input_dev *dev,
			const struct input_device_id *id)
{
	struct input_handle *handle;
	int error;

	/*
	 * Donut treats BTN_MOUSE + REL_X/REL_Y as a trackball and turns the
	 * PS/2 button into its own (0,0) pointer stream.  We only want that
	 * button to emulate a touchscreen press, so hide the relative axes
	 * before Android scans capabilities.
	 */
	if (dev->id.bustype == BUS_I8042) {
		__clear_bit(REL_X, dev->relbit);
		__clear_bit(REL_Y, dev->relbit);
	}

	handle = kzalloc(sizeof(*handle), GFP_KERNEL);
	if (!handle)
		return -ENOMEM;

	handle->dev = dev;
	handle->handler = handler;
	handle->name = "a1ts";

	error = input_register_handle(handle);
	if (error) {
		kfree(handle);
		return error;
	}

	error = input_open_device(handle);
	if (error) {
		input_unregister_handle(handle);
		kfree(handle);
		return error;
	}

	return 0;
}

static void a1ts_disconnect(struct input_handle *handle)
{
	input_close_device(handle);
	input_unregister_handle(handle);
	kfree(handle);
	a1ts_down = false;
}

static const struct input_device_id a1ts_ids[] = {
	{
		/* usb-tablet: the only USB device with both absolute axes */
		.flags = INPUT_DEVICE_ID_MATCH_BUS | INPUT_DEVICE_ID_MATCH_ABSBIT,
		.bustype = BUS_USB,
		.absbit = { (1UL << ABS_X) | (1UL << ABS_Y) },
	},
	{
		/* PL050 PS/2 mouse: receives the button events for a click */
		.flags = INPUT_DEVICE_ID_MATCH_BUS | INPUT_DEVICE_ID_MATCH_RELBIT,
		.bustype = BUS_I8042,
		.relbit = { (1UL << REL_X) | (1UL << REL_Y) },
	},
	{ }
};

static struct input_handler a1ts_handler = {
	.event		= a1ts_event,
	.connect	= a1ts_connect,
	.disconnect	= a1ts_disconnect,
	.name		= "a1ts",
	.id_table	= a1ts_ids,
};

/*
 * Donut's liblog opens /dev/log/main once per process.  The 3.10 tree has no
 * Android logger, so mirror those writes into the kernel console instead;
 * symlink /dev/log/main to this node to get logcat output on the serial port.
 */
static ssize_t a1log_write(struct file *file, const char __user *ubuf,
			   size_t len, loff_t *off)
{
	char in[512];
	char out[512];
	size_t i, n;
	int o = 0;
	bool started = false;

	n = len < sizeof(in) ? len : sizeof(in);
	if (copy_from_user(in, ubuf, n))
		return -EFAULT;

	for (i = 0; i < n && o < (int)sizeof(out) - 1; i++) {
		unsigned char c = in[i];

		if (!started) {
			if (c < 0x20 || c >= 0x7f)
				continue;
			started = true;
		}
		out[o++] = (c >= 0x20 && c < 0x7f) ? c : ' ';
	}
	out[o] = 0;
	if (o)
		printk(KERN_INFO "A1LOG %s\n", out);

	return len;
}

static const struct file_operations a1log_fops = {
	.write		= a1log_write,
	.llseek		= noop_llseek,
};

static struct miscdevice a1log_dev = {
	.minor	= MISC_DYNAMIC_MINOR,
	.name	= "a1log",
	.fops	= &a1log_fops,
	.mode	= 0666,
};

static int __init a1ts_init(void)
{
	int error;

	error = misc_register(&a1log_dev);
	if (error)
		return error;

	a1ts_dev = input_allocate_device();
	if (!a1ts_dev)
		return -ENOMEM;

	a1ts_dev->name = "a1 touchscreen";
	a1ts_dev->id.bustype = BUS_VIRTUAL;

	__set_bit(EV_ABS, a1ts_dev->evbit);
	__set_bit(EV_KEY, a1ts_dev->evbit);
	__set_bit(EV_SYN, a1ts_dev->evbit);
	__set_bit(ABS_X, a1ts_dev->absbit);
	__set_bit(ABS_Y, a1ts_dev->absbit);
	__set_bit(BTN_TOUCH, a1ts_dev->keybit);
	input_set_abs_params(a1ts_dev, ABS_X, 0, A1TS_MAX, 0, 0);
	input_set_abs_params(a1ts_dev, ABS_Y, 0, A1TS_MAX, 0, 0);

	error = input_register_device(a1ts_dev);
	if (error)
		return error;

	error = input_register_handler(&a1ts_handler);
	if (error)
		return error;

	return 0;
}
device_initcall(a1ts_init);
