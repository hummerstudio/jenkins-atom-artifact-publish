package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The artifact-publish atom, started the way the runner starts it, against the fixture in {@code tools/fake_repo.py}.
 *
 * <p>The checks the old {@code tools/smoke.sh} made: the URL shape per provider, the auth header, the sha256 header,
 * path templates, a 401 as a user problem, the opt-out, and a missing credential.
 */
class ArtifactPublishIT {

    private Atom atom;
    private final List<Process> servers = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @AfterEach
    void tearDown() {
        servers.forEach(Process::destroy);
        servers.clear();
    }

    /** Start the recording repository on a free port; {@code status} is what it answers an upload with. */
    private String serve(String status) throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Path log = Path.of(System.getProperty("atom.scratch")).resolve("fake-repo-" + port + ".log");
        Files.deleteIfExists(log);
        List<String> command = new ArrayList<>(List.of("python3",
                Path.of(System.getProperty("atom.module")).resolve("tools/fake_repo.py").toString(),
                String.valueOf(port), log.toString()));
        if (status != null) {
            command.addAll(List.of("--status", status));
        }
        Process server = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(
                        Path.of(System.getProperty("atom.scratch")).resolve("fake-repo-" + port + ".out").toFile()))
                .start();
        servers.add(server);
        for (int attempt = 0; attempt < 100; attempt++) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return "http://127.0.0.1:" + port;
            } catch (IOException notYet) {
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("the fake repository did not start");
    }

    private List<Map<String, Object>> uploads(String base) throws IOException {
        Path log = Path.of(System.getProperty("atom.scratch"))
                .resolve("fake-repo-" + base.substring(base.lastIndexOf(':') + 1) + ".log");
        List<Map<String, Object>> records = new ArrayList<>();
        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                records.add(Json.parseObject(line));
            }
        }
        return records;
    }

    /** A workspace with two publishable outputs. */
    private Atom workspace() {
        return atom.file("build/libs/app-1.0.0.jar", "jar-bytes")
                .file("reports/index.html", "<html/>");
    }

    @Test
    @DisplayName("artifactory: /artifactory/<repo>/<template>, Basic auth, a sha256 header, the byte count")
    void uploadsToArtifactory() throws Exception {
        String base = serve(null);
        AtomResult run = workspace().input("{\"provider\": \"artifactory\", \"baseUrl\": \"" + base + "\","
                + " \"repository\": \"libs-release\", \"username\": \"deployer\", \"password\": \"s3cret\","
                + " \"files\": \"build/libs/*.jar\", \"pathTemplate\": \"acme/app/1.0.0/{name}\","
                + " \"properties\": \"build=42;os=mac\"}").run();

        run.assertOk();
        assertEquals("1", run.field("uploaded"));
        assertEquals("9", run.field("bytes"));

        Map<String, Object> request = uploads(base).get(0);
        assertEquals("/artifactory/libs-release/acme/app/1.0.0/app-1.0.0.jar?properties=build%3D42%3Bos%3Dmac",
                request.get("path"));
        assertEquals("Basic ZGVwbG95ZXI6czNjcmV0", request.get("authorization"), "basic auth");
        assertTrue(String.valueOf(request.get("checksum")).matches("[0-9a-f]{64}"),
                "sha256 header missing: " + request.get("checksum"));
    }

    @Test
    @DisplayName("nexus: /repository/<repo>/<template>, bearer auth, the file's content type")
    void uploadsToNexus() throws Exception {
        String base = serve(null);
        AtomResult run = workspace().input("{\"provider\": \"nexus\", \"baseUrl\": \"" + base + "\","
                + " \"repository\": \"releases\", \"token\": \"nxt\", \"files\": \"reports/index.html\","
                + " \"pathTemplate\": \"site/{name}\"}").run();

        run.assertOk();
        Map<String, Object> request = uploads(base).get(0);
        assertEquals("/repository/releases/site/index.html", request.get("path"));
        assertEquals("Bearer nxt", request.get("authorization"), "bearer auth");
        assertEquals("text/html; charset=utf-8", request.get("contentType"));
    }

    @Test
    @DisplayName("several files in one call, and {path} keeps the layout")
    void uploadsSeveralFilesKeepingTheLayout() throws Exception {
        String base = serve(null);
        AtomResult run = workspace().input("{\"provider\": \"artifactory\", \"baseUrl\": \"" + base + "\","
                + " \"repository\": \"r\", \"token\": \"t\", \"files\": \"**/*.jar, **/*.html\","
                + " \"pathTemplate\": \"{path}\"}").run();

        run.assertOk();
        assertEquals("2", run.field("uploaded"), "two files expected");
        assertTrue(uploads(base).stream()
                        .anyMatch(record -> String.valueOf(record.get("path"))
                                .equals("/artifactory/r/build/libs/app-1.0.0.jar")),
                "path template {path}: " + uploads(base));
    }

    @Test
    @DisplayName("401 is a credentials problem (USER), and the status is reported")
    void reportsA401AsAUserProblem() throws Exception {
        String base = serve("401");
        AtomResult run = workspace().input("{\"provider\": \"artifactory\", \"baseUrl\": \"" + base + "\","
                + " \"repository\": \"r\", \"token\": \"bad\", \"files\": \"**/*.jar\"}").run();

        run.assertRefused();
        assertEquals("1", run.field("errorType"), "401 should be USER(1): " + run.file("output.json"));
        assertTrue(run.file("output.json").contains("HTTP 401"), "status not reported: " + run.file("output.json"));
    }

    @Test
    @DisplayName("failOnError=false records the failure and moves on")
    void optOutRecordsTheFailure() throws Exception {
        String base = serve("401");
        AtomResult run = workspace().input("{\"provider\": \"artifactory\", \"baseUrl\": \"" + base + "\","
                + " \"repository\": \"r\", \"token\": \"bad\", \"files\": \"**/*.jar\","
                + " \"failOnError\": \"false\"}").run();

        run.assertOk();
        assertEquals("1", run.field("failed"));
    }

    @Test
    @DisplayName("no credential is refused before anything is sent")
    void refusesWithoutCredentials() throws Exception {
        String base = serve(null);
        AtomResult run = workspace().input("{\"provider\": \"artifactory\", \"baseUrl\": \"" + base + "\","
                + " \"repository\": \"r\", \"files\": \"nope/*.jar\"}").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("credentials are required"), run.file("output.json"));
    }
}
