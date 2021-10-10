package in.bytehue.osgifx.console.application.handler;

import static in.bytehue.osgifx.console.event.topics.ConfigurationActionEventTopics.CONFIGURAION_DELETED_EVENT_TOPIC;

import javax.inject.Inject;
import javax.inject.Named;

import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.core.services.events.IEventBroker;
import org.eclipse.fx.core.log.Log;
import org.eclipse.fx.core.log.Logger;

import in.bytehue.osgifx.console.agent.ConsoleAgent;
import in.bytehue.osgifx.console.supervisor.ConsoleSupervisor;

public final class ConfigurationDeleteHandler {

    @Log
    @Inject
    private Logger logger;

    @Inject
    private IEventBroker eventBroker;

    @Inject
    private ConsoleSupervisor supervisor;

    @Execute
    public void execute(@Named("pid") final String pid) {
        final ConsoleAgent agent = supervisor.getAgent();
        if (supervisor.getAgent() == null) {
            logger.error("Remote agent cannot be connected");
            return;
        }
        try {
            agent.deleteConfiguration(pid);
            eventBroker.send(CONFIGURAION_DELETED_EVENT_TOPIC, pid);
        } catch (final Exception e) {
            logger.error("Configuration with PID " + pid + "cannot be deleted", e);
        }
    }

}