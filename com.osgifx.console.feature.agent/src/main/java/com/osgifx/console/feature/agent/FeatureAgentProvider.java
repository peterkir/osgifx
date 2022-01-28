/*******************************************************************************
 * Copyright 2022 Amit Kumar Mondal
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
package com.osgifx.console.feature.agent;

import static org.osgi.framework.Constants.BUNDLE_SYMBOLICNAME;
import static org.osgi.framework.Constants.BUNDLE_VERSION;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.AbstractMap.SimpleEntry;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.fx.core.log.FluentLogger;
import org.eclipse.fx.core.log.LoggerFactory;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Version;
import org.osgi.framework.dto.BundleDTO;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.feature.Feature;
import org.osgi.service.feature.FeatureBundle;
import org.osgi.service.feature.FeatureConfiguration;
import org.osgi.service.feature.FeatureService;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.osgifx.console.feature.FeatureBundleDTO;
import com.osgifx.console.feature.FeatureConfigurationDTO;
import com.osgifx.console.feature.FeatureDTO;
import com.osgifx.console.feature.IdDTO;
import com.osgifx.console.update.FeatureAgent;

@Component
public final class FeatureAgentProvider implements FeatureAgent {

    private static final int          DEFAULT_START_LEVEL                 = Integer.getInteger("launch.startlevel.default", 100);
    private static final int          MAX_REDIRECTS                       = 50;
    private static final long         CONNECTION_TIMEOUT_IN_MILLISECONDS  = Duration.ofSeconds(15).toMillis();
    private static final long         READ_TIMEOUT_IN_MILLISECONDS        = Duration.ofSeconds(20).toMillis();
    private static final String       USER_AGENT                          = "osgi.fx";
    private static final String       LOCATION_PREFIX                     = "osgifx-feature:";
    private static final String       TEMP_DIRECTORY_PREFIX               = "osgifx.console_";
    private static final String       STARTLEVEL_KEY                      = "startlevel";
    private static final String       CONFIG_KEY                          = "features";
    private static final String       BUNDLES_DIRECTORY                   = "bundles";
    private static final String       FEATURE_STORAGE_PID                 = "osgifx.features";
    private static final String       BND_LAUNCHER_BUNDLE_LOCATION_PREFIX = "reference:file:";
    private static final List<String> ACCEPTABLE_MIME_TYPES               = ImmutableList.of("application/zip", "application/octet-stream");

    @Reference
    private LoggerFactory         factory;
    @Reference
    private FeatureService        featureService;
    @Reference
    private ConfigurationAdmin    configAdmin;
    @Activate
    private BundleContext         bundleContext;
    private FluentLogger          logger;
    private Path                  downloadDirectory;
    private Path                  extractionDirectory;
    private URI                   lastAccessedRepoURI;
    private Map<File, FeatureDTO> lastReadFeatures;
    private Set<Path>             checkForUpdatesExtractionDirs;

    @Activate
    void activate() throws IOException {
        checkForUpdatesExtractionDirs = Sets.newHashSet();
        logger                        = FluentLogger.of(factory.createLogger(getClass().getName()));
        downloadDirectory             = Files.createTempDirectory(TEMP_DIRECTORY_PREFIX);
        extractionDirectory           = Files.createTempDirectory(TEMP_DIRECTORY_PREFIX);
    }

    @Deactivate
    void deactivate() throws IOException {
        cleanDirectory(downloadDirectory);
        cleanDirectory(extractionDirectory);

        deleteDirectory(downloadDirectory);
        deleteDirectory(extractionDirectory);
    }

    @Override
    public Map<File, FeatureDTO> readFeatures(final File archive) throws Exception {
        return readFeatures(archive, extractionDirectory);
    }

    private Map<File, FeatureDTO> readFeatures(final File archive, final Path extractionDirectory) throws Exception {
        logger.atInfo().log("Reading archive: %s", archive);

        final List<File>            features = extractFeatures(archive, extractionDirectory);
        final Map<File, FeatureDTO> result   = Maps.newHashMap();

        for (final File file : features) {
            final Feature    feature = featureService.readFeature(new FileReader(file));
            final FeatureDTO dto     = FeatureHelper.toFeature(feature);
            result.put(file, dto);
        }
        lastReadFeatures = result;
        return result;
    }

    @Override
    public Map<File, FeatureDTO> readFeatures(final URL archiveURL) throws Exception {
        return readFeatures(archiveURL, extractionDirectory);
    }

    private Map<File, FeatureDTO> readFeatures(final URL archiveURL, final Path extractionDirectory) throws Exception {
        logger.atInfo().log("Reading archive: %s", archiveURL);
        if (lastAccessedRepoURI != null && lastAccessedRepoURI.equals(archiveURL.toURI()) && lastReadFeatures != null) {
            logger.atDebug().log("Last accessed repo URL is as same as '%s' which has been earlier cached locally", archiveURL);
            return lastReadFeatures;
        }
        File file = null;
        try {
            file = downloadArchive(archiveURL);
            final Map<File, FeatureDTO> features = readFeatures(file, extractionDirectory);
            for (final FeatureDTO feature : features.values()) {
                feature.archiveURL = archiveURL.toString();
            }
            lastReadFeatures    = features;
            lastAccessedRepoURI = archiveURL.toURI();
            return features;
        } finally {
            if (file != null) {
                Files.deleteIfExists(file.toPath());
                logger.atInfo().log("Downloaded archive deleted from %s", file);
            }
        }
    }

    @Override
    public Entry<File, FeatureDTO> readFeature(final URL archiveURL, final String featureID) throws Exception {
        logger.atInfo().log("Reading feature '%s' from '%s'", featureID, archiveURL);
        final Map<File, FeatureDTO> readFeatures = readFeatures(archiveURL);
        for (final Entry<File, FeatureDTO> entry : readFeatures.entrySet()) {
            final FeatureDTO feature = entry.getValue();
            if (checkIdEquals(feature.id, featureID)) {
                return new SimpleEntry<>(entry.getKey(), entry.getValue());
            }
        }
        return null;
    }

    @Override
    public FeatureDTO updateOrInstall(final File featureJson, final String archiveURL) throws Exception {
        logger.atInfo().log("Updating or installing feature: %s", featureJson);
        final Feature feature = featureService.readFeature(new FileReader(featureJson));
        try {
            // validate all required constraints before processing the installation request
            logger.atInfo().log("Validating all bundles before processing");
            for (final FeatureBundle bundle : feature.getBundles()) {
                validateBundle(bundle, featureJson);
            }
            // install or update the bundles
            logger.atInfo().log("Installing or updating bundles");
            final List<FeatureBundle> bundles = Lists.newArrayList(feature.getBundles());
            bundles.sort(Comparator.comparingInt(b -> getStartLevel(b.getMetadata())));
            for (final FeatureBundle bundle : bundles) {
                installOrUpdateBundle(bundle, featureJson, getStartLevel(bundle.getMetadata()));
            }
            // update configurations
            logger.atInfo().log("Updating configurations");
            for (final Entry<String, FeatureConfiguration> entry : feature.getConfigurations().entrySet()) {
                final FeatureConfiguration configuration = entry.getValue();
                updateConfiguration(configuration);
            }
            final FeatureDTO dto = FeatureHelper.toFeature(feature);
            dto.archiveURL = archiveURL;
            storeFeature(dto);
            return dto;
        } catch (final Exception e) {
            // atomic operation - if any exception occurs remove the partially installed bundles and configurations
            remove(FeatureHelper.toFeature(feature));
            throw e;
        }
    }

    @Override
    public Collection<FeatureDTO> getInstalledFeatures() {
        try {
            final Configuration              configuration = configAdmin.getConfiguration(FEATURE_STORAGE_PID, "?");
            final Dictionary<String, Object> properties    = configuration.getProperties();
            if (properties == null) {
                return Collections.emptyList();
            }
            final Object features = properties.get(CONFIG_KEY);

            final Gson gson = new Gson();
            if (features == null) {
                return Collections.emptyList();
            }

            final String       fs     = (String) features;
            final FeatureDTO[] result = gson.fromJson(fs, FeatureDTO[].class);
            return ImmutableList.copyOf(result);
        } catch (final IOException e) {
            // should not happen as location check has been disabled
            return Collections.emptyList();
        }
    }

    @Override
    public FeatureDTO remove(final String featureId) throws Exception {
        logger.atInfo().log("Removing feature: %s", featureId);
        final Optional<FeatureDTO> featureToBeRemoved = removeFeature(featureId);
        if (featureToBeRemoved.isPresent()) {
            final FeatureDTO feature = featureToBeRemoved.get();
            remove(feature);
            return feature;
        }
        return null;
    }

    @Override
    public Collection<FeatureDTO> checkForUpdates() throws Exception {
        logger.atInfo().log("Checking for updates");
        final Map<File, FeatureDTO>  updateAvailableFeatures = Maps.newHashMap();
        final Collection<FeatureDTO> installedFeatures       = getInstalledFeatures();
        if (installedFeatures.isEmpty()) {
            logger.atInfo().log("No features exist. Therefore, skipped checking for updates.");
            return Collections.emptyList();
        }
        try {
            for (final FeatureDTO installedFeature : installedFeatures) {
                final String reporURL      = installedFeature.archiveURL;
                final Path   tempDirectory = Files.createTempDirectory(TEMP_DIRECTORY_PREFIX);
                checkForUpdatesExtractionDirs.add(tempDirectory);
                final Map<File, FeatureDTO> onlineFeatures = readFeatures(new URL(reporURL), tempDirectory);
                for (final Entry<File, FeatureDTO> onlineFeatureEntry : onlineFeatures.entrySet()) {
                    final File       onlineFeatureFile = onlineFeatureEntry.getKey();
                    final FeatureDTO onlineFeature     = onlineFeatureEntry.getValue();
                    if (idEquals(installedFeature, onlineFeature) && hasUpdates(installedFeature, onlineFeature)) {
                        updateAvailableFeatures.put(onlineFeatureFile, onlineFeature);
                    }
                }
            }
        } finally {
            for (final Path extDir : checkForUpdatesExtractionDirs) {
                Files.deleteIfExists(extDir);
            }
        }
        return updateAvailableFeatures.values();
    }

    public void remove(final FeatureDTO feature) throws Exception {
        // uninstall all associated bundles
        for (final FeatureBundleDTO bundle : feature.bundles) {
            logger.atInfo().log("Uninstalling feature bundle - '%s'", bundle.id.artifactId + ":" + bundle.id.version);
            uninstallBundle(bundle);
        }
        // remove all associated configurations
        for (final FeatureConfigurationDTO config : feature.configurations.values()) {
            logger.atInfo().log("Removing feature configuration - '%s'", config.pid);
            removeConfiguration(config);
        }
    }

    private boolean hasUpdates(final FeatureDTO installedFeature, final FeatureDTO onlineFeature) {
        final Version installedFeatureVersion = new Version(installedFeature.id.version);
        final Version onlineFeatureVersion    = new Version(onlineFeature.id.version);
        return onlineFeatureVersion.compareTo(installedFeatureVersion) > 0;
    }

    private boolean idEquals(final FeatureDTO installedFeature, final FeatureDTO onlineFeature) {
        final String installedFeatureId = onlineFeature.id.groupId + ":" + onlineFeature.id.artifactId;
        final String onlineFeatureId    = installedFeature.id.groupId + ":" + installedFeature.id.artifactId;
        return Objects.equals(installedFeatureId, onlineFeatureId);
    }

    private void installOrUpdateBundle(final FeatureBundle bundle, final File featureJson, final int startLevel) throws Exception {
        final Optional<Bundle> existingBundle = getExistingBundle(bundle);
        final String           bsn            = bundle.getID().getArtifactId();
        final String           version        = bundle.getID().getVersion();
        final Optional<File>   bundleFile     = findBundleInBundlesDirectory(featureJson.getParentFile(), bsn, version);

        if (existingBundle.isPresent()) {
            final Bundle    b   = existingBundle.get();
            final BundleDTO dto = b.adapt(BundleDTO.class);
            logger.atInfo().log("There exists a bundle with the same bsn and version - '%s', ", dto);

            try (InputStream is = new FileInputStream(bundleFile.get())) {
                logger.atInfo().log("Updating bundle - '%s', ", dto);
                b.update(is);

                logger.atInfo().log("Setting start level to %s", startLevel);
                final BundleStartLevel sl = b.adapt(BundleStartLevel.class);
                sl.setStartLevel(startLevel);

                logger.atInfo().log("Bundle updated - '%s', ", b.adapt(BundleDTO.class));
            }
        } else {
            logger.atInfo().log("No bundle with the same bsn - '%s' and version - '%s' exists, ", bsn, version);

            try (InputStream is = new FileInputStream(bundleFile.get())) {
                logger.atInfo().log("Installing bundle - '%s', ", bsn);
                final Bundle installedBundle = bundleContext.installBundle(LOCATION_PREFIX + bsn, is);

                final BundleStartLevel sl = installedBundle.adapt(BundleStartLevel.class);
                logger.atInfo().log("Setting start level to %s", startLevel);
                sl.setStartLevel(startLevel);

                final BundleDTO dto = installedBundle.adapt(BundleDTO.class);

                logger.atInfo().log("Bundle installed - '%s', ", dto);
                installedBundle.start();
                logger.atInfo().log("Bundle started - '%s', ", dto);
            }
        }
    }

    private int getStartLevel(final Map<String, Object> metadata) {
        final Object startLevel = metadata.get(STARTLEVEL_KEY);
        if (startLevel != null) {
            return Integer.parseInt(startLevel.toString());
        }
        return DEFAULT_START_LEVEL;
    }

    private void uninstallBundle(final FeatureBundleDTO bundle) throws BundleException {
        final Optional<Bundle> existingBundle = getExistingBundle(bundle);
        if (existingBundle.isPresent()) {
            final Bundle    b   = existingBundle.get();
            final BundleDTO dto = b.adapt(BundleDTO.class);
            b.uninstall();
            logger.atInfo().log("Feature bundle '%s' uninstsalled", dto);
        }
    }

    private void validateBundle(final FeatureBundle bundle, final File featureJson) throws Exception {
        final String         bsn            = bundle.getID().getArtifactId();
        final String         version        = bundle.getID().getVersion();
        final Optional<File> bundleFile     = findBundleInBundlesDirectory(featureJson.getParentFile(), bsn, version);
        final boolean        isSystemBundle = checkIfSystemBundle(bundle);
        if (isSystemBundle) {
            throw new RuntimeException("Cannot use bundle with bsn '" + bsn + "' as it cannot be updated");
        }
        if (!bundleFile.isPresent()) {
            throw new RuntimeException("Bundle with BSN '" + bsn + "' is not found in 'bundles' directory inside the archive");
        }
    }

    /**
     * Ensures that no external feature would be able to update any bundle
     * which has been delivered with the application
     */
    private boolean checkIfSystemBundle(final FeatureBundle bundle) {
        // @formatter:off
        return Stream.of(bundleContext.getBundles())
                     .filter(b -> b.getSymbolicName().equals(bundle.getID().getArtifactId()))
                     .anyMatch(b -> b.getLocation()
                     .startsWith(BND_LAUNCHER_BUNDLE_LOCATION_PREFIX));
        // @formatter:on
    }

    private Optional<File> findBundleInBundlesDirectory(final File directory, final String bsn, final String version) throws Exception {
        final File bundleDir = new File(directory, BUNDLES_DIRECTORY);
        if (!bundleDir.exists()) {
            throw new RuntimeException(
                    "Feature associated bundles are missing. Make sure they are kept in the 'bundles' directory inside the archive.");
        }
        final File[] files = bundleDir.listFiles();
        for (final File f : files) {
            if (matchBundle(f, bsn, version)) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }

    private boolean matchBundle(final File file, final String bsn, final String version) throws Exception {
        final String symbolicName  = readAttributeFromManifest(file, BUNDLE_SYMBOLICNAME);
        final String bundleVersion = readAttributeFromManifest(file, BUNDLE_VERSION);
        return symbolicName.equals(bsn) && bundleVersion.equals(version);
    }

    private void updateConfiguration(final FeatureConfiguration configuration) throws Exception {
        final Optional<String> factoryPid = configuration.getFactoryPid();
        final String           pid        = configuration.getPid();
        if (factoryPid.isPresent()) {
            final Configuration factoryConfiguration = configAdmin.createFactoryConfiguration(factoryPid.get(), "?");
            factoryConfiguration.updateIfDifferent(new Hashtable<>(configuration.getValues()));
        } else {
            final Configuration config = configAdmin.getConfiguration(pid, "?");
            config.updateIfDifferent(new Hashtable<>(configuration.getValues()));
        }
    }

    private void removeConfiguration(final FeatureConfigurationDTO configuration) throws Exception {
        final String factoryPid = configuration.factoryPid;
        final String pid        = configuration.pid;
        if (factoryPid != null) {
            logger.atInfo().log("Removing factory configuration - '%s'", factoryPid);
            final Configuration[] factoryConfigurations = configAdmin.listConfigurations("(service.factoryPid=" + factoryPid + ")");
            for (final Configuration config : factoryConfigurations) {
                final String persistentId = config.getPid();
                logger.atInfo().log("Removing configuration - '%s'", persistentId);
                config.delete();
                logger.atInfo().log("Removed configuration - '%s'", persistentId);
            }
        } else {
            logger.atInfo().log("Removing non-factory configuration - '%s'", pid);
            final Configuration config       = configAdmin.getConfiguration(pid, "?");
            final String        persistentId = config.getPid();
            config.delete();
            logger.atInfo().log("Removed configuration - '%s'", persistentId);
        }
    }

    private Optional<Bundle> getExistingBundle(final FeatureBundleDTO bundle) {
        return Stream.of(bundleContext.getBundles()).filter(b -> b.getSymbolicName().equals(bundle.id.artifactId)).findAny();
    }

    private Optional<Bundle> getExistingBundle(final FeatureBundle bundle) {
        return Stream.of(bundleContext.getBundles()).filter(b -> b.getSymbolicName().equals(bundle.getID().getArtifactId())).findAny();
    }

    private static String readAttributeFromManifest(final File jarResource, final String attribute) throws Exception {
        try (FileInputStream is = new FileInputStream(jarResource); JarInputStream jarStream = new JarInputStream(is);) {
            final Manifest manifest = jarStream.getManifest();
            if (manifest == null) {
                throw new RuntimeException(jarResource + " is not a valid JAR");
            }
            final String value = manifest.getMainAttributes().getValue(attribute);
            if (value.contains(";")) {
                return value.split(";")[0];
            }
            return value;
        }
    }

    private File downloadArchive(final URL url) throws Exception {
        logger.atInfo().log("Downloading feature archive from URL '%s'", url);

        HttpURLConnection httpConnection = null;
        final File        outputPath     = new File(downloadDirectory.toFile(), "archive.zip");
        try (final FileOutputStream fileOutputStream = new FileOutputStream(outputPath)) {
            httpConnection = createHttpConnection(url.toURI(), 0);
            final String contentType = httpConnection.getContentType();
            if (!ACCEPTABLE_MIME_TYPES.contains(contentType)) {
                throw new RuntimeException("The URL is not supported. Please use a URL that refers to a ZIP archive.");
            }
            try (final InputStream is = getInputStream(httpConnection);
                    ReadableByteChannel readableByteChannel = Channels.newChannel(url.openStream())) {
                final FileChannel fileChannel = fileOutputStream.getChannel();
                fileChannel.transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
            } catch (final MalformedURLException e) {
                logger.atError().withException(e).log("Invalid URL - '%s'", url);
                throw e;
            } catch (final IOException e) {
                logger.atError().withException(e).log("Download failed from '%s' to '%s'", url, outputPath);
                throw e;
            }
        } finally {
            if (httpConnection != null) {
                httpConnection.disconnect();
            }
        }
        logger.atInfo().log("Downloaded feature archive from URL '%s'", url);
        return outputPath;
    }

    private HttpURLConnection createHttpConnection(final URI uri, final int redirectCount) {
        if (redirectCount >= MAX_REDIRECTS) {
            throw new RuntimeException(String.format("Could not establish connection to '%s'. Reached max limit of %s redirects",
                    uri.toString(), MAX_REDIRECTS));
        }
        HttpURLConnection connection = null;
        try {
            final URL downloadUrl = uri.toURL();
            connection = (HttpURLConnection) downloadUrl.openConnection();
            connection.setConnectTimeout((int) CONNECTION_TIMEOUT_IN_MILLISECONDS);
            connection.setReadTimeout((int) READ_TIMEOUT_IN_MILLISECONDS);
            connection.setInstanceFollowRedirects(false);
            setUserAgentHeader(connection);
            while (isRedirected(connection)) {
                final String redirectLocation = connection.getHeaderField("Location");
                connection.disconnect();
                logger.atDebug().log("Following URL redirect: '%s' -> '%s'", uri, redirectLocation);
                connection = createHttpConnection(new URI(redirectLocation), redirectCount + 1);
            }
            return connection;
        } catch (IOException | URISyntaxException | IllegalArgumentException e) {
            final String detail = String.format("While downloading from URL '%s': %s - %s", uri, e.getClass().getSimpleName(),
                    e.getMessage());
            if (connection != null) {
                connection.disconnect();
            }
            throw new RuntimeException(detail, e);
        }
    }

    private void setUserAgentHeader(final HttpURLConnection httpConnection) {
        httpConnection.setRequestProperty("User-Agent", USER_AGENT);
    }

    private boolean isRedirected(final HttpURLConnection httpConnection) throws IOException {
        final int responseCode = httpConnection.getResponseCode();
        return responseCode == HttpURLConnection.HTTP_MOVED_PERM || responseCode == HttpURLConnection.HTTP_MOVED_TEMP;
    }

    private InputStream getInputStream(final HttpURLConnection httpConnection) throws IOException {
        InputStream inputStream = null;
        try {
            final int responseCode = httpConnection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                final String detail = String.format("Could not download file from URL '%s': Remote server response was '%s' - '%s'.",
                        httpConnection.getURL(), responseCode, httpConnection.getResponseMessage());
                throw new RuntimeException(detail);
            }
            inputStream = httpConnection.getInputStream();
        } catch (final IOException e) {
            final String detail = String.format("While downloading from URL '%s': %s - %s", httpConnection.getURL(),
                    e.getClass().getSimpleName(), e.getMessage());
            throw new RuntimeException(detail, e);
        } finally {
            if (inputStream != null) {
                inputStream.close();
            }
        }
        return inputStream;
    }

    private List<File> extractFeatures(final File archive, final Path extractionDirectory) throws IOException {
        unzip(archive, extractionDirectory);
        final File[] files = extractionDirectory.toFile().listFiles((FilenameFilter) (dir, name) -> name.endsWith(".json"));
        return Stream.of(files).collect(Collectors.toList());
    }

    public void cleanDirectory(final Path path) throws IOException {
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(final Path file, final IOException e) {
                return handleException(e);
            }

            private FileVisitResult handleException(final IOException e) {
                logger.atError().withException(e).log("Exception occurred while walking down the file tree");
                return FileVisitResult.TERMINATE;
            }

            @Override
            public FileVisitResult postVisitDirectory(final Path dir, final IOException e) throws IOException {
                if (e != null) {
                    return handleException(e);
                }
                if (path != dir) {
                    Files.delete(dir);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void unzip(final File zipFilePath, final Path extractionDirectory) throws IOException {
        cleanDirectory(extractionDirectory);
        try (final ZipFile zipFile = new ZipFile(zipFilePath)) {
            final Enumeration<?> enu = zipFile.entries();
            while (enu.hasMoreElements()) {
                final ZipEntry zipEntry = (ZipEntry) enu.nextElement();

                final String name           = zipEntry.getName();
                final long   size           = zipEntry.getSize();
                final long   compressedSize = zipEntry.getCompressedSize();
                logger.atInfo().log("[Extracting Feature Archive] Name: %s | Size: %s | Compressed Size: %s ", name, size, compressedSize);

                final File file = new File(extractionDirectory.toFile(), name);
                if (name.endsWith("/")) {
                    file.mkdirs();
                    continue;
                }

                final File parent = file.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }

                try (InputStream is = zipFile.getInputStream(zipEntry); FileOutputStream fos = new FileOutputStream(file)) {
                    final byte[] bytes = new byte[1024];
                    int          length;
                    while ((length = is.read(bytes)) >= 0) {
                        fos.write(bytes, 0, length);
                    }
                }
            }
        }
    }

    private void storeFeature(final FeatureDTO dto) throws IOException {
        final Configuration        configuration = configAdmin.getConfiguration(FEATURE_STORAGE_PID, "?");
        Dictionary<String, Object> properties    = configuration.getProperties();
        if (properties == null) {
            properties = new Hashtable<>();
        }
        final Object features = properties.get(CONFIG_KEY);
        final Gson   gson     = new Gson();
        if (features == null) {
            final String json = gson.toJson(Lists.newArrayList(dto).toArray(new FeatureDTO[0]));

            final Map<String, Object> props = new HashMap<>();
            props.put("features", json);

            configuration.update(new Hashtable<>(props));
        } else {
            final FeatureDTO[] result = gson.fromJson(features.toString(), FeatureDTO[].class);

            final List<FeatureDTO> toBeStored = Lists.newArrayList(result);
            toBeStored.removeIf(f -> checkIdEquals(f.id, dto.id));
            toBeStored.add(dto);

            final String              json  = gson.toJson(toBeStored);
            final Map<String, Object> props = new HashMap<>();

            props.put("features", json);
            configuration.update(new Hashtable<>(props));
        }
    }

    private Optional<FeatureDTO> removeFeature(final String featureId) throws IOException {
        final Configuration              configuration = configAdmin.getConfiguration(FEATURE_STORAGE_PID, "?");
        final Dictionary<String, Object> properties    = configuration.getProperties();
        final Object                     features      = properties.get(CONFIG_KEY);
        final Gson                       gson          = new Gson();
        if (features == null) {
            return Optional.empty();
        }
        final String       fs     = (String) features;
        final FeatureDTO[] result = gson.fromJson(fs, FeatureDTO[].class);

        final List<FeatureDTO>     finalList           = Lists.newArrayList(result);
        final Optional<FeatureDTO> featuredToBeRemoved = finalList.stream().filter(f -> checkIdEquals(f.id, featureId)).findAny();
        finalList.removeIf(f -> checkIdEquals(f.id, featureId));

        final String              json  = gson.toJson(finalList);
        final Map<String, Object> props = new HashMap<>();

        props.put("features", json);
        configuration.update(new Hashtable<>(props));
        return featuredToBeRemoved;
    }

    private boolean checkIdEquals(final IdDTO id1, final IdDTO id2) {
        // a feature is equal to another if the group ID and artifact ID are equal
        final String idToCompare = id2.groupId + ":" + id2.artifactId;
        return checkIdEquals(id1, idToCompare);
    }

    private boolean checkIdEquals(final IdDTO id, final String featureId) {
        final String idToCompare = id.groupId + ":" + id.artifactId + ":" + id.version;
        return idToCompare.equals(featureId);
    }

    private void deleteDirectory(final Path path) throws IOException {
        final File   file    = path.toFile();
        final String absPath = file.getAbsolutePath();
        if (Files.deleteIfExists(file.toPath())) {
            logger.atInfo().log("Removed '%s' directory", absPath);
        }
    }

}