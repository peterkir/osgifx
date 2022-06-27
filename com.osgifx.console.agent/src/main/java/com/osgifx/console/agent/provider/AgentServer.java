/*******************************************************************************
 * Copyright 2021-2022 Amit Kumar Mondal
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy
 * of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/
package com.osgifx.console.agent.provider;

import static com.osgifx.console.agent.dto.XResultDTO.ERROR;
import static com.osgifx.console.agent.dto.XResultDTO.SKIPPED;
import static com.osgifx.console.agent.dto.XResultDTO.SUCCESS;
import static com.osgifx.console.agent.helper.AgentHelper.createResult;
import static com.osgifx.console.agent.helper.AgentHelper.packageNotWired;
import static com.osgifx.console.agent.provider.PackageWirings.Type.CM;
import static com.osgifx.console.agent.provider.PackageWirings.Type.DMT;
import static com.osgifx.console.agent.provider.PackageWirings.Type.EVENT_ADMIN;
import static com.osgifx.console.agent.provider.PackageWirings.Type.R7_LOGGER;
import static com.osgifx.console.agent.provider.PackageWirings.Type.SCR;
import static com.osgifx.console.agent.provider.PackageWirings.Type.USER_ADMIN;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;
import static org.osgi.framework.Constants.BUNDLE_SYMBOLICNAME;
import static org.osgi.framework.Constants.BUNDLE_VERSION;
import static org.osgi.framework.Constants.SYSTEM_BUNDLE_ID;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecuteResultHandler;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.Executor;
import org.apache.commons.exec.PumpStreamHandler;
import org.osgi.dto.DTO;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Filter;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.Version;
import org.osgi.framework.dto.BundleDTO;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.framework.wiring.dto.BundleRevisionDTO;
import org.osgi.resource.Capability;
import org.osgi.resource.Requirement;
import org.osgi.resource.dto.CapabilityDTO;
import org.osgi.resource.dto.RequirementDTO;
import org.osgi.util.tracker.ServiceTracker;

import com.osgifx.console.agent.Agent;
import com.osgifx.console.agent.admin.XBundleAdmin;
import com.osgifx.console.agent.admin.XComponentAdmin;
import com.osgifx.console.agent.admin.XConfigurationAdmin;
import com.osgifx.console.agent.admin.XDmtAdmin;
import com.osgifx.console.agent.admin.XEventAdmin;
import com.osgifx.console.agent.admin.XHcAdmin;
import com.osgifx.console.agent.admin.XHeapAdmin;
import com.osgifx.console.agent.admin.XHttpAdmin;
import com.osgifx.console.agent.admin.XLogReaderAdmin;
import com.osgifx.console.agent.admin.XLoggerAdmin;
import com.osgifx.console.agent.admin.XMetaTypeAdmin;
import com.osgifx.console.agent.admin.XPropertyAdmin;
import com.osgifx.console.agent.admin.XServiceAdmin;
import com.osgifx.console.agent.admin.XThreadAdmin;
import com.osgifx.console.agent.admin.XUserAdmin;
import com.osgifx.console.agent.dto.ConfigValue;
import com.osgifx.console.agent.dto.DmtDataType;
import com.osgifx.console.agent.dto.XBundleDTO;
import com.osgifx.console.agent.dto.XBundleLoggerContextDTO;
import com.osgifx.console.agent.dto.XComponentDTO;
import com.osgifx.console.agent.dto.XConfigurationDTO;
import com.osgifx.console.agent.dto.XDmtNodeDTO;
import com.osgifx.console.agent.dto.XHealthCheckDTO;
import com.osgifx.console.agent.dto.XHealthCheckResultDTO;
import com.osgifx.console.agent.dto.XHeapUsageDTO;
import com.osgifx.console.agent.dto.XHeapdumpDTO;
import com.osgifx.console.agent.dto.XHttpComponentDTO;
import com.osgifx.console.agent.dto.XMemoryInfoDTO;
import com.osgifx.console.agent.dto.XPropertyDTO;
import com.osgifx.console.agent.dto.XResultDTO;
import com.osgifx.console.agent.dto.XRoleDTO;
import com.osgifx.console.agent.dto.XRoleDTO.Type;
import com.osgifx.console.agent.dto.XServiceDTO;
import com.osgifx.console.agent.dto.XThreadDTO;
import com.osgifx.console.agent.extension.AgentExtension;
import com.osgifx.console.agent.handler.ClassloaderLeakDetector;
import com.osgifx.console.agent.handler.OSGiEventHandler;
import com.osgifx.console.agent.handler.OSGiLogListener;
import com.osgifx.console.agent.helper.AgentHelper;
import com.osgifx.console.agent.link.RemoteRPC;
import com.osgifx.console.agent.redirector.ConsoleRedirector;
import com.osgifx.console.agent.redirector.GogoRedirector;
import com.osgifx.console.agent.redirector.NullRedirector;
import com.osgifx.console.agent.redirector.RedirectOutput;
import com.osgifx.console.agent.redirector.Redirector;
import com.osgifx.console.agent.redirector.SocketRedirector;
import com.osgifx.console.supervisor.Supervisor;

import aQute.bnd.exceptions.Exceptions;
import aQute.lib.converter.Converter;
import aQute.lib.converter.TypeReference;

@SuppressWarnings("rawtypes")
public final class AgentServer implements Agent, Closeable {

    private static final long          RESULT_TIMEOUT   = Duration.ofSeconds(20).toMillis();
    private static final long          WATCHDOG_TIMEOUT = Duration.ofSeconds(30).toMillis();
    private static final AtomicInteger sequence         = new AtomicInteger(1000);
    private static final Pattern       BSN_PATTERN      = Pattern.compile("\\s*([^;\\s]+).*");

    private Supervisor                   remote;
    private BundleContext                context;
    private final Map<String, String>    installed  = new HashMap<>();
    public volatile boolean              quit;
    private Redirector                   redirector = new NullRedirector();
    private RemoteRPC<Agent, Supervisor> remoteRPC;
    private ClassloaderLeakDetector      leakDetector;
    private BundleStartTimeCalculator    bundleStartTimeCalculator;

    private final ServiceTracker<Object, Object>                 scrTracker;
    private final ServiceTracker<Object, Object>                 metatypeTracker;
    private final ServiceTracker<Object, Object>                 dmtAdminTracker;
    private final ServiceTracker<Object, Object>                 userAdminTracker;
    private final ServiceTracker<Object, Object>                 eventAdminTracker;
    private final ServiceTracker<Object, Object>                 loggerAdminTracker;
    private final ServiceTracker<Object, Object>                 configAdminTracker;
    private final ServiceTracker<Object, Object>                 gogoCommandsTracker;
    private final ServiceTracker<Object, Object>                 felixHcExecutorTracker;
    private final ServiceTracker<Object, Object>                 httpServiceRuntimeTracker;
    private final ServiceTracker<AgentExtension, AgentExtension> agentExtensionTracker;

    private ServiceTracker<Object, Object> logReaderTracker;

    private Closeable              osgiLogListenerCloser;
    private ServiceRegistration<?> osgiEventListenerServiceReg;

    private final Set<String>                           gogoCommands    = new CopyOnWriteArraySet<>();
    private final Map<String, AgentExtension<DTO, DTO>> agentExtensions = new ConcurrentHashMap<>();

    public AgentServer(final BundleContext context, final ClassloaderLeakDetector leakDetector,
            final BundleStartTimeCalculator bundleStartTimeCalculator) throws Exception {

        requireNonNull(context, "Bundle context cannot be null");
        requireNonNull(leakDetector, "Leak detector cannot be null");
        requireNonNull(bundleStartTimeCalculator, "Bundle start time calculator cannot be null");

        this.context                   = context;
        this.leakDetector              = leakDetector;
        this.bundleStartTimeCalculator = bundleStartTimeCalculator;

        final Filter gogoCommandFilter = context.createFilter("(osgi.command.scope=*)");

        metatypeTracker           = new ServiceTracker<>(context, "org.osgi.service.metatype.MetaTypeService", null);
        dmtAdminTracker           = new ServiceTracker<>(context, "org.osgi.service.dmt.DmtAdmin", null);
        userAdminTracker          = new ServiceTracker<>(context, "org.osgi.service.useradmin.UserAdmin", null);
        loggerAdminTracker        = new ServiceTracker<>(context, "org.osgi.service.log.admin.LoggerAdmin", null);
        eventAdminTracker         = new ServiceTracker<>(context, "org.osgi.service.event.EventAdmin", null);
        configAdminTracker        = new ServiceTracker<>(context, "org.osgi.service.cm.ConfigurationAdmin", null);
        felixHcExecutorTracker    = new ServiceTracker<>(context, "org.apache.felix.hc.api.execution.HealthCheckExecutor", null);
        scrTracker                = new ServiceTracker<>(context, "org.osgi.service.component.runtime.ServiceComponentRuntime", null);
        httpServiceRuntimeTracker = new ServiceTracker<>(context, "org.osgi.service.http.runtime.HttpServiceRuntime", null);
        agentExtensionTracker     = new ServiceTracker<AgentExtension, AgentExtension>(context, AgentExtension.class, null) {

                                      @Override
                                      @SuppressWarnings("unchecked")
                                      public AgentExtension addingService(final ServiceReference<AgentExtension> reference) {
                                          final Object name = reference.getProperty(AgentExtension.PROPERTY_KEY);
                                          if (name == null) {
                                              return null;
                                          }
                                          final AgentExtension tracked = super.addingService(reference);
                                          agentExtensions.put(name.toString(), tracked);
                                          return tracked;
                                      }

                                      @Override
                                      public void modifiedService(final ServiceReference<AgentExtension> reference,
                                              final AgentExtension service) {
                                          removedService(reference, service);
                                          addingService(reference);
                                      }

                                      @Override
                                      public void removedService(final ServiceReference<AgentExtension> reference,
                                              final AgentExtension service) {
                                          final Object name = reference.getProperty(AgentExtension.PROPERTY_KEY);
                                          if (name == null) {
                                              return;
                                          }
                                          agentExtensions.remove(name);
                                      }
                                  };
        gogoCommandsTracker       = new ServiceTracker<Object, Object>(context, gogoCommandFilter, null) {
                                      @Override
                                      public Object addingService(final ServiceReference<Object> reference) {
                                          final String   scope     = String.valueOf(reference.getProperty("osgi.command.scope"));
                                          final String[] functions = adapt(reference.getProperty("osgi.command.function"));
                                          addCommand(scope, functions);
                                          return super.addingService(reference);
                                      }

                                      @Override
                                      public void removedService(final ServiceReference<Object> reference, final Object service) {
                                          final String   scope     = String.valueOf(reference.getProperty("osgi.command.scope"));
                                          final String[] functions = adapt(reference.getProperty("osgi.command.function"));
                                          removeCommand(scope, functions);
                                      }

                                      private String[] adapt(final Object value) {
                                          if (value instanceof String[]) {
                                              return (String[]) value;
                                          }
                                          return new String[] { value.toString() };
                                      }

                                      private void addCommand(final String scope, final String... commands) {
                                          Stream.of(commands).forEach(cmd -> gogoCommands.add(scope + ":" + cmd));
                                      }

                                      private void removeCommand(final String scope, final String... commands) {
                                          Stream.of(commands).forEach(cmd -> gogoCommands.remove(scope + ":" + cmd));
                                      }
                                  };
        scrTracker.open();
        metatypeTracker.open();
        dmtAdminTracker.open();
        userAdminTracker.open();
        loggerAdminTracker.open();
        eventAdminTracker.open();
        configAdminTracker.open();
        gogoCommandsTracker.open();
        agentExtensionTracker.open();
        felixHcExecutorTracker.open();
        httpServiceRuntimeTracker.open();
    }

    @Override
    public BundleDTO installWithData(final String location, final byte[] data, final int startLevel) throws Exception {
        return installBundleWithData(location, data, startLevel, true);
    }

    @Override
    public XResultDTO installWithMultipleData(final Collection<byte[]> data, final int startLevel) {
        requireNonNull(data, "Data cannot be null");

        final XResultDTO    result = new XResultDTO();
        final StringBuilder b      = new StringBuilder();
        try {
            for (final byte[] d : data) {
                try {
                    installBundleWithData(null, d, startLevel, false);
                } catch (final Exception e) {
                    b.append(e.getMessage()).append(System.lineSeparator());
                }
            }
        } catch (final Exception e) {
            result.result = XResultDTO.ERROR;
        } finally {
            result.response = b.toString();
            // if there are no errors at all, perform the refresh operation
            if (result.response.isEmpty()) {
                try {
                    // this sometimes causes https://issues.apache.org/jira/browse/FELIX-3414
                    refresh(true);
                } catch (final Exception e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        result.result = XResultDTO.SUCCESS;
        return result;
    }

    @Override
    public BundleDTO installFromURL(final String location, final String url) throws Exception {
        requireNonNull(location, "Bundle location cannot be null");
        requireNonNull(url, "Bundle URL cannot be null");

        final InputStream is = new URL(url).openStream();
        final Bundle      b  = context.installBundle(location, is);
        installed.put(b.getLocation(), url);
        return toDTO(b);
    }

    @Override
    public String start(final long... ids) {
        requireNonNull(ids, "Bundle IDs cannot be null");

        final StringBuilder sb = new StringBuilder();
        for (final long id : ids) {
            final Bundle bundle = context.getBundle(id);
            try {
                bundle.start();
            } catch (final BundleException e) {
                sb.append(e.getMessage()).append("\n");
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    @Override
    public String stop(final long... ids) {
        requireNonNull(ids, "Bundle IDs cannot be null");

        final StringBuilder sb = new StringBuilder();
        for (final long id : ids) {
            final Bundle bundle = context.getBundle(id);
            try {
                bundle.stop();
            } catch (final BundleException e) {
                sb.append(e.getMessage()).append("\n");
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    @Override
    public String uninstall(final long... ids) {
        requireNonNull(ids, "Bundle IDs cannot be null");

        final StringBuilder sb = new StringBuilder();
        for (final long id : ids) {
            final Bundle bundle = context.getBundle(id);
            try {
                bundle.uninstall();
                installed.remove(bundle.getLocation());
            } catch (final BundleException e) {
                sb.append(e.getMessage()).append("\n");
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    @Override
    public List<BundleRevisionDTO> getBundleRevisons(final long... ids) throws Exception {
        requireNonNull(ids, "Bundle IDs cannot be null");

        Bundle[] bundles;
        if (ids.length == 0) {
            bundles = context.getBundles();
        } else {
            bundles = new Bundle[ids.length];
            for (int i = 0; i < ids.length; i++) {
                bundles[i] = context.getBundle(ids[i]);
                if (bundles[i] == null) {
                    throw new IllegalArgumentException("Bundle " + ids[i] + " does not exist");
                }
            }
        }
        final List<BundleRevisionDTO> revisions = new ArrayList<>(bundles.length);
        for (final Bundle b : bundles) {
            final BundleRevision    resource = b.adapt(BundleRevision.class);
            final BundleRevisionDTO bwd      = toDTO(resource);
            revisions.add(bwd);
        }
        return revisions;
    }

    @Override
    public boolean redirect(final int port) throws Exception {
        if (redirector != null) {
            if (redirector.getPort() == port) {
                return false;
            }
            redirector.close();
            redirector = new NullRedirector();
        }
        if (port == Agent.NONE) {
            return true;
        }
        if (port <= COMMAND_SESSION) {
            try {
                redirector = new GogoRedirector(this, context);
            } catch (final Exception e) {
                throw new IllegalStateException("Gogo is not present in this framework", e);
            }
            return true;
        }
        if (port == Agent.CONSOLE) {
            redirector = new ConsoleRedirector(this);
            return true;
        }
        redirector = new SocketRedirector(this, port);
        return true;
    }

    @Override
    public boolean stdin(final String s) throws Exception {
        if (redirector != null) {
            redirector.stdin(s);
            return true;
        }
        return false;
    }

    @Override
    public String execGogoCommand(final String command) throws Exception {
        requireNonNull(command, "Gogo command cannot be null");

        redirect(COMMAND_SESSION);
        stdin(command);
        final PrintStream ps = redirector.getOut();
        if (ps instanceof RedirectOutput) {
            final RedirectOutput rout = (RedirectOutput) ps;
            return rout.getLastOutput();
        }
        return null;
    }

    @Override
    public String execCliCommand(final String command) {
        requireNonNull(command, "CLI command cannot be null");

        String cmd;
        if (isWindows()) {
            cmd = "cmd.exe /C " + command;
        } else {
            cmd = command;
        }
        try {
            final CommandLine                 cmdLine       = CommandLine.parse(cmd);
            final DefaultExecuteResultHandler resultHandler = new DefaultExecuteResultHandler();
            final ExecuteWatchdog             watchdog      = new ExecuteWatchdog(WATCHDOG_TIMEOUT);
            final Executor                    executor      = new DefaultExecutor();
            final ByteArrayOutputStream       outputStream  = new ByteArrayOutputStream();
            final PumpStreamHandler           streamHandler = new PumpStreamHandler(outputStream);

            executor.setExitValue(1);
            executor.setWatchdog(watchdog);
            executor.setStreamHandler(streamHandler);
            executor.execute(cmdLine, resultHandler);
            resultHandler.waitFor(RESULT_TIMEOUT);

            return outputStream.toString();
        } catch (final Exception e) {
            Thread.currentThread().interrupt();
            return Exceptions.toString(e);
        }
    }

    public void setSupervisor(final Supervisor remote) {
        setRemote(remote);
    }

    private BundleDTO toDTO(final Bundle b) {
        final BundleDTO bd = new BundleDTO();
        bd.id           = b.getBundleId();
        bd.lastModified = b.getLastModified();
        bd.state        = b.getState();
        bd.symbolicName = b.getSymbolicName();
        bd.version      = b.getVersion() == null ? "0" : b.getVersion().toString();
        return bd;
    }

    private BundleRevisionDTO toDTO(final BundleRevision resource) {
        final BundleRevisionDTO brd = new BundleRevisionDTO();
        brd.bundle       = resource.getBundle().getBundleId();
        brd.id           = sequence.getAndIncrement();
        brd.symbolicName = resource.getSymbolicName();
        brd.type         = resource.getTypes();
        brd.version      = resource.getVersion().toString();

        brd.requirements = new ArrayList<>();

        for (final Requirement r : resource.getRequirements(null)) {
            brd.requirements.add(toDTO(brd.id, r));
        }

        brd.capabilities = new ArrayList<>();
        for (final Capability c : resource.getCapabilities(null)) {
            brd.capabilities.add(toDTO(brd.id, c));
        }

        return brd;
    }

    private RequirementDTO toDTO(final int resource, final Requirement r) {
        final RequirementDTO rd = new RequirementDTO();
        rd.id         = sequence.getAndIncrement();
        rd.resource   = resource;
        rd.namespace  = r.getNamespace();
        rd.directives = r.getDirectives();
        rd.attributes = r.getAttributes();
        return rd;
    }

    private CapabilityDTO toDTO(final int resource, final Capability r) {
        final CapabilityDTO rd = new CapabilityDTO();
        rd.id         = sequence.getAndIncrement();
        rd.resource   = resource;
        rd.namespace  = r.getNamespace();
        rd.directives = r.getDirectives();
        rd.attributes = r.getAttributes();
        return rd;
    }

    void cleanup(final int event) throws Exception {
        if (quit) {
            return;
        }
        quit = true;
        redirect(0);
        remoteRPC.close();
    }

    @Override
    public void close() throws IOException {
        try {
            cleanup(-2);

            scrTracker.close();
            metatypeTracker.close();
            dmtAdminTracker.close();
            userAdminTracker.close();
            loggerAdminTracker.close();
            eventAdminTracker.close();
            configAdminTracker.close();
            gogoCommandsTracker.close();
            agentExtensionTracker.close();
            felixHcExecutorTracker.close();
            httpServiceRuntimeTracker.close();

            if (logReaderTracker != null) {
                logReaderTracker.close();
            }
            if (osgiEventListenerServiceReg != null) {
                osgiEventListenerServiceReg.unregister();
            }
            if (osgiLogListenerCloser != null) {
                osgiLogListenerCloser.close();
            }
        } catch (final Exception e) {
            throw new IOException(e);
        }
    }

    @Override
    public void abort() throws Exception {
        cleanup(-3);
    }

    public void setRemote(final Supervisor supervisor) {
        remote = supervisor;
    }

    public Supervisor getSupervisor() {
        return remote;
    }

    public void setEndpoint(final RemoteRPC<Agent, Supervisor> remoteRPC) {
        setRemote(remoteRPC.getRemote());
        this.remoteRPC = remoteRPC;

        // the following can only be initialized if and only if the RPC link is
        // established
        osgiLogListenerCloser       = initOSGiLogging();
        osgiEventListenerServiceReg = initOSGiEventing();
    }

    @Override
    public boolean ping() {
        return true;
    }

    public BundleContext getContext() {
        return context;
    }

    public void refresh(final boolean async) throws InterruptedException {
        final FrameworkWiring wiring = context.getBundle(SYSTEM_BUNDLE_ID).adapt(FrameworkWiring.class);
        if (wiring != null) {
            final CountDownLatch refresh = new CountDownLatch(1);
            wiring.refreshBundles(null, event -> refresh.countDown());
            if (async) {
                return;
            }
            refresh.await();
        }
    }

    private Entry<String, Version> getIdentity(final byte[] data) throws IOException {
        try (JarInputStream jin = new JarInputStream(new ByteArrayInputStream(data))) {
            final Manifest manifest = jin.getManifest();
            if (manifest == null) {
                throw new IllegalArgumentException("No manifest in bundle");
            }
            final Attributes mainAttributes = manifest.getMainAttributes();
            final String     value          = mainAttributes.getValue(BUNDLE_SYMBOLICNAME);
            final Matcher    matcher        = BSN_PATTERN.matcher(value);

            if (!matcher.matches()) {
                throw new IllegalArgumentException("No proper Bundle-SymbolicName in bundle: " + value);
            }

            final String bsn = matcher.group(1);

            String versionString = mainAttributes.getValue(BUNDLE_VERSION);
            if (versionString == null) {
                versionString = "0";
            }

            final Version version = Version.parseVersion(versionString);

            return new AbstractMap.SimpleEntry<>(bsn, version);
        }
    }

    private Set<Bundle> findBundles(final String bsn, final Version version) {
        return Stream.of(context.getBundles()).filter(b -> bsn.equals(b.getSymbolicName()))
                .filter(b -> version == null || version.equals(b.getVersion())).collect(Collectors.toSet());
    }

    private String getLocation(final byte[] data) throws IOException {
        final Map.Entry<String, Version> entry   = getIdentity(data);
        final Set<Bundle>                bundles = findBundles(entry.getKey(), null);
        switch (bundles.size()) {
        case 0:
            return "manual:" + entry.getKey();
        case 1:
            return bundles.iterator().next().getLocation();
        default:
            throw new IllegalArgumentException(
                    "No location specified but there are multiple bundles with the same bsn " + entry.getKey() + ": " + bundles);
        }
    }

    @Override
    public List<XBundleDTO> getAllBundles() {
        return XBundleAdmin.get(context, bundleStartTimeCalculator);
    }

    @Override
    public List<XComponentDTO> getAllComponents() {
        final boolean isScrAvailable = PackageWirings.isScrWired(context);
        if (isScrAvailable) {
            final XComponentAdmin scrAdmin = new XComponentAdmin(scrTracker.getService());
            return scrAdmin.getComponents();
        }
        return Collections.emptyList();
    }

    @Override
    public List<XConfigurationDTO> getAllConfigurations() {
        final boolean isConfigAdminAvailable = PackageWirings.isConfigAdminWired(context);
        final boolean isMetatypeAvailable    = PackageWirings.isMetatypeWired(context);

        final List<XConfigurationDTO> configs = new ArrayList<>();
        if (isConfigAdminAvailable) {
            final XConfigurationAdmin configAdmin = new XConfigurationAdmin(context, configAdminTracker.getService(),
                    metatypeTracker.getService());
            configs.addAll(configAdmin.getConfigurations());
        }
        if (isMetatypeAvailable) {
            final XMetaTypeAdmin metatypeAdmin = new XMetaTypeAdmin(context, configAdminTracker.getService(), metatypeTracker.getService());
            configs.addAll(metatypeAdmin.getConfigurations());
        }
        return configs;
    }

    @Override
    public List<XPropertyDTO> getAllProperties() {
        return XPropertyAdmin.get(context);
    }

    @Override
    public List<XServiceDTO> getAllServices() {
        return XServiceAdmin.get(context);
    }

    @Override
    public List<XThreadDTO> getAllThreads() {
        final XThreadAdmin threadAdmin = new XThreadAdmin(context);
        return threadAdmin.get();
    }

    @Override
    public XDmtNodeDTO readDmtNode(final String rootURI) {
        requireNonNull(rootURI, "DMT node root URI cannot be null");

        final boolean isDmtAdminAvailable = PackageWirings.isDmtAdminWired(context);
        if (isDmtAdminAvailable) {
            final XDmtAdmin dmtAdmin = new XDmtAdmin(dmtAdminTracker.getService());
            return dmtAdmin.readDmtNode(rootURI);
        }
        return null;
    }

    @Override
    public XResultDTO updateDmtNode(final String uri, final Object value, final DmtDataType format) {
        requireNonNull(uri, "DMT node URI cannot be null");
        requireNonNull(value, "DMT value cannot be null");
        requireNonNull(format, "DMT value type cannot be null");

        final boolean isDmtAdminAvailable = PackageWirings.isDmtAdminWired(context);
        if (isDmtAdminAvailable) {
            final XDmtAdmin dmtAdmin = new XDmtAdmin(dmtAdminTracker.getService());
            return dmtAdmin.updateDmtNode(uri, value, format);
        }
        return createResult(SKIPPED, packageNotWired(DMT));
    }

    @Override
    public XResultDTO updateBundleLoggerContext(final String bsn, final Map<String, String> logLevels) {
        final boolean isR7LogAvailable = PackageWirings.isR7LoggerAdminWired(context);
        if (isR7LogAvailable) {
            final boolean      isConfigAdminWired = PackageWirings.isConfigAdminWired(context);
            final XLoggerAdmin loggerAdmin        = new XLoggerAdmin(loggerAdminTracker.getService(), isConfigAdminWired, context);
            return loggerAdmin.updateLoggerContext(bsn, logLevels);
        }
        return createResult(SKIPPED, packageNotWired(R7_LOGGER));
    }

    @Override
    public XResultDTO enableComponentById(final long id) {
        final boolean isScrAvailable = PackageWirings.isScrWired(context);
        if (isScrAvailable) {
            final XComponentAdmin scrAdmin = new XComponentAdmin(scrTracker.getService());
            return scrAdmin.enableComponent(id);
        }
        return createResult(SKIPPED, packageNotWired(SCR));
    }

    @Override
    public XResultDTO enableComponentByName(final String name) {
        requireNonNull(name, "Component name cannot be null");

        final boolean isScrAvailable = PackageWirings.isScrWired(context);
        if (isScrAvailable) {
            final XComponentAdmin scrAdmin = new XComponentAdmin(scrTracker.getService());
            return scrAdmin.enableComponent(name);
        }
        return createResult(SKIPPED, packageNotWired(SCR));
    }

    @Override
    public XResultDTO disableComponentById(final long id) {
        final boolean isScrAvailable = PackageWirings.isScrWired(getContext());
        if (isScrAvailable) {
            final XComponentAdmin scrAdmin = new XComponentAdmin(scrTracker.getService());
            return scrAdmin.disableComponent(id);
        }
        return createResult(SKIPPED, packageNotWired(SCR));
    }

    @Override
    public XResultDTO disableComponentByName(final String name) {
        requireNonNull(name, "Component name cannot be null");

        final boolean isScrAvailable = PackageWirings.isScrWired(getContext());
        if (isScrAvailable) {
            final XComponentAdmin scrAdmin = new XComponentAdmin(scrTracker.getService());
            return scrAdmin.disableComponent(name);
        }
        return createResult(SKIPPED, packageNotWired(SCR));
    }

    @Override
    public XResultDTO createOrUpdateConfiguration(final String pid, final List<ConfigValue> newProperties) {
        requireNonNull(newProperties, "Configuration properties cannot be null");

        try {
            final Map<String, Object> finalProperties = parseProperties(newProperties);
            return createOrUpdateConfig(pid, finalProperties);
        } catch (final Exception e) {
            return createResult(ERROR, "One or more configuration properties cannot be converted to the requested type");
        }
    }

    @Override
    public Map<String, XResultDTO> createOrUpdateConfigurations(final Map<String, Map<String, Object>> configurations) {
        final Map<String, XResultDTO> results = new HashMap<>();
        configurations.forEach((k, v) -> results.put(k, createOrUpdateConfig(k, v)));
        return results;
    }

    @Override
    public XResultDTO deleteConfiguration(final String pid) {
        requireNonNull(pid, "Configuration PID cannot be null");

        final boolean isConfigAdminAvailable = PackageWirings.isConfigAdminWired(getContext());
        if (isConfigAdminAvailable) {
            final XConfigurationAdmin configAdmin = new XConfigurationAdmin(context, configAdminTracker.getService(),
                    metatypeTracker.getService());
            return configAdmin.deleteConfiguration(pid);
        }
        return createResult(SKIPPED, packageNotWired(CM));
    }

    @Override
    public XResultDTO createFactoryConfiguration(final String factoryPid, final List<ConfigValue> newProperties) {
        requireNonNull(factoryPid, "Configuration factory PID cannot be null");
        requireNonNull(newProperties, "Configuration properties cannot be null");

        final boolean isConfigAdminAvailable = PackageWirings.isConfigAdminWired(context);

        if (isConfigAdminAvailable) {
            final XConfigurationAdmin configAdmin = new XConfigurationAdmin(context, configAdminTracker.getService(),
                    metatypeTracker.getService());
            try {
                final Map<String, Object> finalProperties = parseProperties(newProperties);
                return configAdmin.createFactoryConfiguration(factoryPid, finalProperties);
            } catch (final Exception e) {
                return createResult(ERROR, "One or configuration properties cannot be converted to the requested type");
            }
        }
        return createResult(SKIPPED, packageNotWired(CM));
    }

    @Override
    public XResultDTO sendEvent(final String topic, final List<ConfigValue> properties) {
        requireNonNull(topic, "Event topic cannot be null");
        requireNonNull(properties, "Event properties cannot be null");

        final boolean isEventAdminAvailable = PackageWirings.isEventAdminWired(context);
        if (isEventAdminAvailable) {
            final XEventAdmin eventAdmin = new XEventAdmin(eventAdminTracker.getService());
            try {
                final Map<String, Object> finalProperties = parseProperties(properties);
                eventAdmin.sendEvent(topic, finalProperties);
                return createResult(SUCCESS, "Event has been sent successfully");
            } catch (final Exception e) {
                return createResult(ERROR, "Event could not be sent successfully");
            }
        }
        return createResult(SKIPPED, packageNotWired(EVENT_ADMIN));
    }

    @Override
    public XResultDTO postEvent(final String topic, final List<ConfigValue> properties) {
        requireNonNull(topic, "Event topic cannot be null");
        requireNonNull(properties, "Event properties cannot be null");

        final boolean isEventAdminAvailable = PackageWirings.isEventAdminWired(context);
        if (isEventAdminAvailable) {
            final XEventAdmin eventAdmin = new XEventAdmin(eventAdminTracker.getService());
            try {
                final Map<String, Object> finalProperties = parseProperties(properties);
                eventAdmin.postEvent(topic, finalProperties);
                return createResult(SUCCESS, "Event has been sent successfully");
            } catch (final Exception e) {
                return createResult(ERROR, "Event could not be sent successfully");
            }
        }
        return createResult(SKIPPED, packageNotWired(EVENT_ADMIN));
    }

    @Override
    public XMemoryInfoDTO getMemoryInfo() {
        final XMemoryInfoDTO dto = new XMemoryInfoDTO();

        dto.uptime      = getSystemUptime();
        dto.maxMemory   = Runtime.getRuntime().maxMemory();
        dto.freeMemory  = Runtime.getRuntime().freeMemory();
        dto.totalMemory = Runtime.getRuntime().totalMemory();

        return dto;
    }

    @Override
    public Set<String> getGogoCommands() {
        return new HashSet<>(gogoCommands);
    }

    @Override
    public XResultDTO createRole(final String name, final Type type) {
        requireNonNull(name, "Role name cannot be null");
        requireNonNull(type, "Role type name cannot be null");

        final boolean isUserAdminAvailable = PackageWirings.isUserAdminWired(context);
        if (isUserAdminAvailable) {
            try {
                final XUserAdmin userAdmin = new XUserAdmin(userAdminTracker.getService());
                return userAdmin.createRole(name, type);
            } catch (final Exception e) {
                return createResult(ERROR, "The role cannot be created");
            }
        }
        return createResult(SKIPPED, packageNotWired(USER_ADMIN));
    }

    @Override
    public XResultDTO updateRole(final XRoleDTO dto) {
        requireNonNull(dto, "New role information cannot be null");

        final boolean isUserAdminAvailable = PackageWirings.isUserAdminWired(context);
        if (isUserAdminAvailable) {
            try {
                final XUserAdmin userAdmin = new XUserAdmin(userAdminTracker.getService());
                return userAdmin.updateRole(dto);
            } catch (final Exception e) {
                return createResult(ERROR, "The role cannot be updated");
            }
        }
        return createResult(SKIPPED, packageNotWired(USER_ADMIN));
    }

    @Override
    public XResultDTO removeRole(final String name) {
        requireNonNull(name, "Role name cannot be null");

        final boolean isUserAdminAvailable = PackageWirings.isUserAdminWired(context);
        if (isUserAdminAvailable) {
            try {
                final XUserAdmin userAdmin = new XUserAdmin(userAdminTracker.getService());
                return userAdmin.removeRole(name);
            } catch (final Exception e) {
                return createResult(ERROR, "The role cannot be removed");
            }
        }
        return createResult(SKIPPED, packageNotWired(USER_ADMIN));
    }

    @Override
    public List<XRoleDTO> getAllRoles() {
        final boolean isUserAdminAvailable = PackageWirings.isUserAdminWired(context);
        if (isUserAdminAvailable) {
            final XUserAdmin userAdmin = new XUserAdmin(userAdminTracker.getService());
            return userAdmin.getRoles();
        }
        return Collections.emptyList();
    }

    @Override
    public List<XBundleLoggerContextDTO> getBundleLoggerContexts() {
        final boolean isR7LogAvailable = PackageWirings.isR7LoggerAdminWired(context);
        if (isR7LogAvailable) {
            final boolean      isConfigAdminWired = PackageWirings.isConfigAdminWired(context);
            final XLoggerAdmin loggerAdmin        = new XLoggerAdmin(loggerAdminTracker.getService(), isConfigAdminWired, context);
            return loggerAdmin.getLoggerContexts();
        }
        return Collections.emptyList();
    }

    @Override
    public List<XHealthCheckDTO> getAllHealthChecks() {
        final boolean isFelixHcAvailable = PackageWirings.isFelixHcWired(context);
        if (isFelixHcAvailable) {
            final XHcAdmin hcAdmin = new XHcAdmin(context, felixHcExecutorTracker.getService());
            return hcAdmin.getHealthchecks();
        }
        return Collections.emptyList();
    }

    @Override
    public List<XHealthCheckResultDTO> executeHealthChecks(final List<String> tags, final List<String> names) {
        final boolean isFelixHcAvailable = PackageWirings.isFelixHcWired(context);
        if (isFelixHcAvailable) {
            final XHcAdmin hcAdmin = new XHcAdmin(context, felixHcExecutorTracker.getService());
            return hcAdmin.executeHealthChecks(tags, names);
        }
        return Collections.emptyList();
    }

    @Override
    public Map<String, Object> executeExtension(final String name, final Map<String, Object> context) {
        requireNonNull(name, "Agent extension name cannot be null");
        requireNonNull(context, "Agent extension execution context cannot be null");

        if (!agentExtensions.containsKey(name)) {
            throw new RuntimeException("Agent extension with name '" + name + "' doesn't exist");
        }
        try {
            final AgentExtension<DTO, DTO> extension = agentExtensions.get(name);
            final DTO                      cnv       = Converter.cnv(extension.getContextType(), context);
            final DTO                      result    = extension.execute(cnv);
            return Converter.cnv(new TypeReference<Map<String, Object>>() {
            }, result);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Set<XBundleDTO> getClassloaderLeaks() {
        return leakDetector.getSuspiciousBundles();
    }

    @Override
    public List<XHttpComponentDTO> getHttpComponents() {
        final boolean isHttpServiceRuntimeWired = PackageWirings.isHttpServiceRuntimeWired(context);
        if (isHttpServiceRuntimeWired) {
            final Object service = httpServiceRuntimeTracker.getService();
            if (service == null) {
                return Collections.emptyList();
            }
            final XHttpAdmin httpAdmin = new XHttpAdmin(service);
            return httpAdmin.runtime();
        }
        return Collections.emptyList();
    }

    @Override
    public XHeapUsageDTO getHeapUsage() {
        final boolean isJMXWired = PackageWirings.isJmxWired(context);
        return isJMXWired ? XHeapAdmin.init() : null;
    }

    @Override
    public void gc() {
        System.gc();
    }

    @Override
    public XHeapdumpDTO heapdump() throws Exception {
        final boolean isJMXWired = PackageWirings.isJmxWired(context);
        return isJMXWired ? XHeapAdmin.heapdump() : null;
    }

    private long getSystemUptime() {
        final boolean isJMXWired = PackageWirings.isJmxWired(context);
        return isJMXWired ? ManagementFactory.getRuntimeMXBean().getUptime() : 0L;
    }

    private BundleDTO installBundleWithData(String location, final byte[] data, final int startLevel, final boolean shouldRefresh)
            throws IOException, BundleException, InterruptedException {
        requireNonNull(data);

        Bundle installedBundle;
        if (location == null) {
            location = getLocation(data);
        }
        try (InputStream stream = new ByteArrayInputStream(data)) {
            installedBundle = context.getBundle(location);
            if (installedBundle == null) {
                installedBundle = context.installBundle(location, stream);
                installedBundle.adapt(BundleStartLevel.class).setStartLevel(startLevel);
            } else {
                installedBundle.update(stream);
                if (shouldRefresh) {
                    refresh(true);
                }
            }
        }
        return toDTO(installedBundle);
    }

    private Map<String, Object> parseProperties(final List<ConfigValue> newProperties) throws Exception {
        final Map<String, Object> properties = new HashMap<>();
        for (final ConfigValue entry : newProperties) {
            final Object convertedValue = AgentHelper.convert(entry);
            properties.put(entry.key, convertedValue);
        }
        return properties;
    }

    private static boolean isWindows() {
        final String os = System.getProperty("os.name", "generic").toLowerCase(ENGLISH);
        return os.contains("win");
    }

    private XResultDTO createOrUpdateConfig(final String pid, final Map<String, Object> newProperties) {
        requireNonNull(pid, "Configuration PID cannot be null");
        requireNonNull(newProperties, "Configuration properties cannot be null");

        final boolean isConfigAdminAvailable = PackageWirings.isConfigAdminWired(context);
        if (isConfigAdminAvailable) {
            final XConfigurationAdmin configAdmin = new XConfigurationAdmin(context, configAdminTracker.getService(),
                    metatypeTracker.getService());
            try {
                return configAdmin.createOrUpdateConfiguration(pid, newProperties);
            } catch (final Exception e) {
                return createResult(ERROR, "One or more configuration properties cannot be converted to the requested type");
            }
        }
        return createResult(SKIPPED, packageNotWired(CM));
    }

    private ServiceRegistration<?> initOSGiEventing() {
        final boolean isEventAdminAvailable = PackageWirings.isEventAdminWired(context);
        if (isEventAdminAvailable) {
            final Dictionary<String, Object> properties = new Hashtable<>();
            properties.put("event.topics", "*");
            return context.registerService("org.osgi.service.event.EventHandler", new OSGiEventHandler(remoteRPC.getRemote()), properties);
        }
        return null;
    }

    private Closeable initOSGiLogging() {
        final boolean isLogAvailable = PackageWirings.isLogWired(context);
        if (isLogAvailable) {
            final OSGiLogListener logListener = new OSGiLogListener(remoteRPC.getRemote(), bundleStartTimeCalculator);
            return trackLogReader(logListener);
        }
        return null;
    }

    private Closeable trackLogReader(final OSGiLogListener logListener) {
        logReaderTracker = new ServiceTracker<Object, Object>(context, "org.osgi.service.log.LogReaderService", null) {

            @Override
            public Object addingService(final ServiceReference<Object> reference) {
                final boolean isLogAvailable = PackageWirings.isLogWired(context);
                final Object  service        = super.addingService(reference);
                if (isLogAvailable) {
                    XLogReaderAdmin.register(service, logListener);
                }
                return service;
            }

            @Override
            public void removedService(final ServiceReference<Object> reference, final Object service) {
                final boolean isLogAvailable = PackageWirings.isLogWired(context);
                if (isLogAvailable) {
                    XLogReaderAdmin.unregister(service, logListener);
                }
            }
        };
        logReaderTracker.open();
        return () -> logReaderTracker.close();
    }

}
