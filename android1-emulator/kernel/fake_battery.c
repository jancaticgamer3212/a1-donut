/*
 * Static power supplies for the Android 1.6 guest.
 *
 * Donut's BatteryService reads /sys/class/power_supply/{battery,ac,usb} through
 * com_android_server_BatteryService.  When those files are missing it treats
 * the battery as absent and empty and puts up a "Please connect charger"
 * dialog.  QEMU's versatilepb has no battery device, so expose a fixed,
 * fully charged one.
 */
#include <linux/init.h>
#include <linux/power_supply.h>

static enum power_supply_property fake_battery_props[] = {
	POWER_SUPPLY_PROP_STATUS,
	POWER_SUPPLY_PROP_HEALTH,
	POWER_SUPPLY_PROP_PRESENT,
	POWER_SUPPLY_PROP_TECHNOLOGY,
	POWER_SUPPLY_PROP_CAPACITY,
	POWER_SUPPLY_PROP_TEMP,
	POWER_SUPPLY_PROP_VOLTAGE_NOW,
};

static int fake_battery_get_property(struct power_supply *psy,
				     enum power_supply_property psp,
				     union power_supply_propval *val)
{
	switch (psp) {
	case POWER_SUPPLY_PROP_STATUS:
		val->intval = POWER_SUPPLY_STATUS_CHARGING;
		break;
	case POWER_SUPPLY_PROP_HEALTH:
		val->intval = POWER_SUPPLY_HEALTH_GOOD;
		break;
	case POWER_SUPPLY_PROP_PRESENT:
		val->intval = 1;
		break;
	case POWER_SUPPLY_PROP_TECHNOLOGY:
		val->intval = POWER_SUPPLY_TECHNOLOGY_LION;
		break;
	case POWER_SUPPLY_PROP_CAPACITY:
		val->intval = 100;
		break;
	case POWER_SUPPLY_PROP_TEMP:
		val->intval = 250;
		break;
	case POWER_SUPPLY_PROP_VOLTAGE_NOW:
		val->intval = 4000000;
		break;
	default:
		return -EINVAL;
	}
	return 0;
}

static enum power_supply_property fake_online_props[] = {
	POWER_SUPPLY_PROP_ONLINE,
};

static int fake_online_get_property(struct power_supply *psy,
				    enum power_supply_property psp,
				    union power_supply_propval *val)
{
	if (psp != POWER_SUPPLY_PROP_ONLINE)
		return -EINVAL;
	val->intval = 1;
	return 0;
}

static struct power_supply fake_battery = {
	.name		= "battery",
	.type		= POWER_SUPPLY_TYPE_BATTERY,
	.properties	= fake_battery_props,
	.num_properties	= ARRAY_SIZE(fake_battery_props),
	.get_property	= fake_battery_get_property,
};

static struct power_supply fake_ac = {
	.name		= "ac",
	.type		= POWER_SUPPLY_TYPE_MAINS,
	.properties	= fake_online_props,
	.num_properties	= ARRAY_SIZE(fake_online_props),
	.get_property	= fake_online_get_property,
};

static struct power_supply fake_usb = {
	.name		= "usb",
	.type		= POWER_SUPPLY_TYPE_USB,
	.properties	= fake_online_props,
	.num_properties	= ARRAY_SIZE(fake_online_props),
	.get_property	= fake_online_get_property,
};

static int __init fake_power_init(void)
{
	int ret;

	ret = power_supply_register(NULL, &fake_battery);
	if (ret)
		return ret;
	ret = power_supply_register(NULL, &fake_ac);
	if (ret)
		return ret;
	return power_supply_register(NULL, &fake_usb);
}
device_initcall(fake_power_init);
