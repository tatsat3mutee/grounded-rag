package io.groundedrag.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.groundedrag.domain.DocumentStatus;
import io.groundedrag.domain.SpaceId;
import io.groundedrag.ingestion.IngestionService;
import io.groundedrag.store.KnowledgeStore;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Drives the MCP endpoint with the official MCP Java client over a real socket, like an agent would. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "rag.rate-limit.burst=1000")
class McpServerTest {

    static final String USER = "dev-key-change-me-0001"; // spaces: handbook, product

    @LocalServerPort
    int port;

    @Autowired
    IngestionService ingestion;

    @Autowired
    KnowledgeStore store;

    @BeforeEach
    void ingest() throws Exception {
        var s = ingestion.submit(SpaceId.of("product"), "webhooks-mcp.md", "text/markdown",
                "# Webhooks\nFailed webhook deliveries are retried up to 8 times over 24 hours with exponential backoff."
                        .getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 200 && store.findDocument(SpaceId.of("product"), s.document().id()).orElseThrow().status()
                != DocumentStatus.ACTIVE; i++) {
            Thread.sleep(20);
        }
    }

    private McpSyncClient client(String apiKey) {
        var request = HttpRequest.newBuilder();
        if (apiKey != null) {
            request.header("X-API-Key", apiKey);
        }
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint("/mcp").requestBuilder(request).build();
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build();
    }

    private static String text(McpSchema.CallToolResult r) {
        return r.content().stream().filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text()).reduce("", String::concat);
    }

    @Test
    void agentsSeeBothToolsWithDescriptions() {
        try (McpSyncClient mcp = client(USER)) {
            mcp.initialize();
            List<McpSchema.Tool> tools = mcp.listTools().tools();
            assertThat(tools).extracting(McpSchema.Tool::name).contains("search_documents", "ask_documents");
            assertThat(tools).allSatisfy(t -> assertThat(t.description()).isNotBlank());
        }
    }

    @Test
    void searchReturnsCitablePassagesFromAllowedSpaces() {
        try (McpSyncClient mcp = client(USER)) {
            mcp.initialize();
            var r = mcp.callTool(new McpSchema.CallToolRequest("search_documents",
                    Map.of("query", "how many times are failed webhook deliveries retried")));
            assertThat(r.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(text(r)).contains("webhooks-mcp.md").contains("8 times").contains("\"number\":1");
        }
    }

    @Test
    void askReturnsAVerifiedAnswerWithCitations() {
        try (McpSyncClient mcp = client(USER)) {
            mcp.initialize();
            var r = mcp.callTool(new McpSchema.CallToolRequest("ask_documents",
                    Map.of("question", "How many times are failed webhook deliveries retried?", "spaces", List.of("product"))));
            assertThat(text(r)).contains("8 times").contains("\"verified\":true").contains("webhooks-mcp.md");
        }
    }

    @Test
    void spacesOutsideTheKeyAreRefused() {
        try (McpSyncClient mcp = client(USER)) {
            mcp.initialize();
            var r = mcp.callTool(new McpSchema.CallToolRequest("search_documents",
                    Map.of("query", "budget", "spaces", List.of("finance"))));
            assertThat(r.isError()).isTrue();
            assertThat(text(r)).contains("finance");
        }
    }

    @Test
    void withoutAKeyTheServerCannotBeReached() {
        try (McpSyncClient mcp = client(null)) {
            assertThatThrownBy(mcp::initialize).isInstanceOf(RuntimeException.class);
        }
    }
}
