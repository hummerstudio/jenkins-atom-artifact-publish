package com.jenkinschina.atom.artifactpublish;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Glob;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Publish build outputs to an artifact repository.
 *
 * <p>Artifactory (17k installs) and Nexus (6k) are the same step with a different URL shape:
 * {@code PUT <base>/artifactory/<repo>/<path>} versus {@code PUT <base>/repository/<repo>/<path>}, both with basic
 * auth or a token. One atom, a {@code provider} switch — same reasoning as the chat notifier, and the checksum is
 * computed once for both so the repository's own verification has something to compare.
 */
public class ArtifactPublishAtom implements AtomTask {

    static final List<String> PROVIDERS = List.of("artifactory", "nexus");

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String provider = context.getParam("provider", "artifactory").trim().toLowerCase(Locale.ROOT);
        String baseUrl = context.getParam("baseUrl", "").trim();
        String repository = context.getParam("repository", "").trim();
        String username = context.getParam("username", "");
        String password = context.getParam("password", "");
        String token = context.getParam("token", "");
        String patterns = context.getParam("files", "**/*.jar");
        String pathTemplate = context.getParam("pathTemplate", "{name}");
        String properties = context.getParam("properties", "").trim();
        boolean failOnError = context.getBooleanParam("failOnError", true);
        int timeoutSeconds = Math.max(10, Math.min(context.getIntParam("timeoutSeconds", 60), 600));

        if (!PROVIDERS.contains(provider)) {
            return AtomResult.error(ErrorType.USER, 40001,
                    "unknown provider '" + provider + "'; known: " + String.join(", ", PROVIDERS));
        }
        if (baseUrl.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40002, "baseUrl is required");
        }
        if (repository.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40003, "repository is required");
        }
        if (username.isEmpty() && password.isEmpty() && token.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40004,
                    "credentials are required: username/password, or token");
        }

        List<Path> files = Glob.find(workspace, patterns);
        if (files.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40005, "nothing matched " + patterns);
        }

        String prefix = baseUrl.replaceAll("/+$", "")
                + (provider.equals("artifactory") ? "/artifactory/" : "/repository/")
                + repository.replaceAll("^/+", "").replaceAll("/+$", "");

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
        List<String> urls = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        long bytes = 0;

        for (Path file : files) {
            Path relative = workspace.relativize(file);
            String name = file.getFileName().toString();
            String target = pathTemplate
                    .replace("{name}", name)
                    .replace("{path}", relative.toString().replace('\\', '/'))
                    .replace("{dir}", relative.getParent() == null ? ""
                            : relative.getParent().toString().replace('\\', '/'));
            target = target.replaceAll("^/+", "");
            String url = prefix + "/" + target + (properties.isEmpty() ? "" : "?properties=" + uriEncode(properties));

            context.log("[publish] " + provider + " PUT " + url + " (" + Files.size(file) + " bytes)");
            try {
                byte[] content = Files.readAllBytes(file);
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .header("Content-Type", contentType(name))
                        .header("X-Checksum-Sha256", sha256(content))
                        .header("User-Agent", "jenkins-atom-artifact-publish")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(content));
                if (!token.isBlank()) {
                    builder.header("Authorization", "Bearer " + token);
                } else if (!username.isEmpty() || !password.isEmpty()) {
                    String pair = username + ":" + password;
                    builder.header("Authorization",
                            "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
                }
                HttpResponse<String> response = client.send(builder.build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    String reason = "HTTP " + status + " uploading " + name + ": " + excerpt(response.body());
                    if (failOnError) {
                        return AtomResult.error(status == 401 || status == 403 ? ErrorType.USER : ErrorType.THIRD_PART,
                                status == 401 || status == 403 ? 40006 : 40007, reason);
                    }
                    failures.add(reason);
                    context.log("[publish] warning: " + reason);
                    continue;
                }
                bytes += content.length;
                urls.add(url);
            } catch (java.net.http.HttpConnectTimeoutException e) {
                return AtomResult.error(ErrorType.ENVIRONMENT, 40008,
                        "could not reach " + baseUrl + " within " + timeoutSeconds + "s");
            } catch (java.io.IOException e) {
                return AtomResult.error(ErrorType.THIRD_PART, 40009,
                        "uploading " + name + " failed: " + e.getMessage());
            }
        }

        Map<String, String> outputs = new LinkedHashMap<>();
        outputs.put("uploaded", String.valueOf(urls.size()));
        outputs.put("failed", String.valueOf(failures.size()));
        outputs.put("bytes", String.valueOf(bytes));
        outputs.put("urls", String.join("\n", urls.size() > 20 ? urls.subList(0, 20) : urls));
        if (!urls.isEmpty()) {
            outputs.put("first", urls.get(0));
        }
        context.log("[publish] " + urls.size() + " file(s), " + bytes + " bytes"
                + (failures.isEmpty() ? "" : ", " + failures.size() + " failed"));

        AtomResult result = AtomResult.success();
        outputs.forEach(result::data);
        return result;
    }

    static String contentType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jar") || lower.endsWith(".war") || lower.endsWith(".zip")) {
            return "application/java-archive";
        }
        if (lower.endsWith(".xml")) {
            return "application/xml";
        }
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        return "application/octet-stream";
    }

    static String sha256(byte[] content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest(content)) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static String uriEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String excerpt(String body) {
        String oneLine = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "…" : oneLine;
    }
}
