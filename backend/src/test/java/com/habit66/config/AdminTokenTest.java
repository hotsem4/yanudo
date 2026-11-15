package com.habit66.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 관리자 토큰 검사를 실제 Tomcat 위에서 확인한다. MockMvc 는 경로 디코딩이 컨테이너와 달라
 * 인코딩된 경로·경로 매개변수 우회를 재현하지 못한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.data-dir=build/test-data/admin-token",
        "app.admin-token=test-token-123",
        "app.tts.auto-generate=false",
        "app.reminder.enabled=false"
})
class AdminTokenTest {

    private static final String TOKEN = "test-token-123";

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void adminApiNeedsTheToken() throws Exception {
        HttpResponse<String> denied = send("GET", "/api/v1/admin/settings", null);
        assertThat(denied.statusCode()).isEqualTo(401);
        assertThat(denied.body()).contains("\"error\":\"UNAUTHORIZED\"").contains("\"message\"");

        assertThat(send("GET", "/api/v1/admin/settings", "wrong").statusCode()).isEqualTo(401);
        assertThat(send("GET", "/api/v1/admin/tts/status", null).statusCode()).isEqualTo(401);

        HttpResponse<String> allowed = send("GET", "/api/v1/admin/settings", TOKEN);
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.body()).contains("\"start_date\"");
    }

    @Test
    void learningApiStaysOpen() throws Exception {
        assertThat(send("GET", "/api/v1/dashboard", null).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/v1/push/vapid-public-key", null).statusCode()).isEqualTo(200);
    }

    /** 원시 URI 만 보던 필터는 아래 경로들을 관리자 경로로 알아보지 못해 토큰 없이 통과시켰다. */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/%61dmin/settings",
            "/api/v1/admin;x=1/settings",
            "/api/v1/admin/settings;jsessionid=abc",
            "/api/%761/admin/settings",
            "/%61pi/v1/admin/settings",
            "/api/v1/%41DMIN/settings",
            "/api/v1//admin/settings",
            "/api/v1/./admin/settings",
            "/api/v1/x/../admin/settings"
    })
    void disguisedAdminPathsNeverSucceedWithoutTheToken(String path) throws Exception {
        HttpResponse<String> response = send("GET", path, null);

        assertThat(response.statusCode()).as(path).isNotEqualTo(200);
        assertThat(response.body()).as(path).doesNotContain("start_date");
    }

    @Test
    void disguisedWriteIsRejectedAndChangesNothing() throws Exception {
        String before = send("GET", "/api/v1/admin/settings", TOKEN).body();

        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/%61dmin/settings/start-date"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"start_date\":\"2020-01-01\"}"))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(send("GET", "/api/v1/admin/settings", TOKEN).body()).isEqualTo(before);
    }

    private HttpResponse<String> send(String method, String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            builder.header("X-Admin-Token", token);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** URI.create 는 경로를 정규화하지 않으므로 인코딩·세미콜론이 그대로 서버에 간다. */
    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
