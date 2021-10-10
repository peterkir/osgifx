package in.bytehue.osgifx.console.event.topics;

public final class ConfigurationActionEventTopics {

    private ConfigurationActionEventTopics() {
        throw new IllegalAccessError("Cannot be instantiated");
    }

    public static final String CONFIGURAION_ACTION_EVENT_TOPICS = "osgi/fx/console/configuration/*";
    public static final String CONFIGURAION_UPDATED_EVENT_TOPIC = "osgi/fx/console/configuration/updated";
    public static final String CONFIGURAION_DELETED_EVENT_TOPIC = "osgi/fx/console/configuration/deleted";

}