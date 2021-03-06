package me.wulfmarius.modinstaller.update;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.stream.Stream;

import org.springframework.http.ResponseEntity;

import me.wulfmarius.modinstaller.ProgressListeners;
import me.wulfmarius.modinstaller.repository.source.*;
import me.wulfmarius.modinstaller.rest.RestClient;
import me.wulfmarius.modinstaller.utils.JsonUtils;

public class UpdateChecker {

    private static final String LATEST_RELEASE_URL = "https://api.github.com/repos/WulfMarius/Mod-Installer/releases/latest";

    private final RestClient restClient;
    private final UpdateState state;

    private Path downloadPath;
    private Path[] otherVersions;

    public UpdateChecker(RestClient restClient) {
        super();
        this.restClient = restClient;
        this.state = this.readState();
    }

    public boolean areOtherVersionsPresent() {
        return this.otherVersions != null && this.otherVersions.length > 0;
    }

    public void downloadNewVersion(Path directory, ProgressListeners progressListeners) {
        this.downloadPath = directory.resolve(this.getState().getAsset().getName());
        this.restClient.downloadAsset(this.state.getAsset().getDownloadUrl(), this.downloadPath, progressListeners);
    }

    public void findLatestVersion() {
        if (!this.canCheckForNewVersion()) {
            return;
        }

        ResponseEntity<String> response = this.restClient.fetch(LATEST_RELEASE_URL, this.state.getEtag());
        if (response.getStatusCode().is2xxSuccessful()) {
            GithubRelease latestRelease = this.restClient.deserialize(response, GithubRelease.class, GithubRelease::new);
            this.state.setLatestVersion(latestRelease.getName());

            GithubAsset[] assets = latestRelease.getAssets();
            if (assets != null && assets.length == 1) {
                this.state.setAsset(assets[0]);
            } else {
                this.state.setAsset(null);
            }
            this.state.setReleaseUrl(latestRelease.getUrl());
        }

        this.state.setEtag(response.getHeaders().getETag());
        this.state.setChecked(new Date());
        this.writeState();
    }

    public void findOtherVersions(Path path) {
        Path currentJar = this.getCurrentJarPath();

        try (Stream<Path> stream = Files.list(path)) {
            this.otherVersions = stream.filter(Files::isRegularFile).filter(file -> !file.getFileName().equals(currentJar))
                    .filter(file -> file.getFileName().toString().startsWith("mod-installer-")
                            && file.getFileName().toString().endsWith(".jar"))
                    .toArray(Path[]::new);
        } catch (IOException e) {
            this.otherVersions = new Path[0];
        }
    }

    public String getDownloadURL() {
        return this.state.getReleaseUrl();
    }

    public String getLatestVersion() {
        return this.state.getLatestVersion();
    }

    public Path[] getOtherVersions() {
        return this.otherVersions;
    }

    public UpdateState getState() {
        return this.state;
    }

    public boolean hasDownloadedNewVersion() {
        return this.downloadPath != null && Files.exists(this.downloadPath);
    }

    public boolean isNewVersionAvailable(String version) {
        return this.state.getLatestVersion() != null && !this.state.getLatestVersion().equals(version);
    }

    public void startNewVersion() throws IOException {
        new ProcessBuilder("java", "-jar", this.downloadPath.getFileName().toString()).inheritIO().start();
    }

    private boolean canCheckForNewVersion() {
        if (this.state.getChecked() == null) {
            return true;
        }

        return this.state.getChecked().toInstant().plus(5, ChronoUnit.MINUTES).isBefore(Instant.now());
    }

    private Path getCurrentJarPath() {
        try {
            return Paths.get(UpdateChecker.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getFileName();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private Path getUpdateCheckStatePath() {
        return Paths.get(System.getProperty("java.io.tmpdir"), "tld-mod-installer-update-check-state.json");
    }

    private UpdateState readState() {
        try {
            Path path = this.getUpdateCheckStatePath();
            if (Files.exists(path)) {
                return JsonUtils.deserialize(path, UpdateState.class);
            }
        } catch (IOException e) {
            // ignore
        }

        return new UpdateState();
    }

    private void writeState() {
        try {
            Path path = this.getUpdateCheckStatePath();
            JsonUtils.serialize(path, this.state);
        } catch (IOException e) {
            // ignore
        }
    }
}
