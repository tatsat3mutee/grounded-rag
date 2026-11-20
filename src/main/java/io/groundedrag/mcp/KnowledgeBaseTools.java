package io.groundedrag.mcp;

import java.util.List;
import java.util.Set;

import io.groundedrag.chat.ChatAnswer;
import io.groundedrag.chat.RagService;
import io.groundedrag.domain.RetrievedChunk;
import io.groundedrag.domain.SpaceId;
import io.groundedrag.retrieval.ContextAssembler;
import io.groundedrag.retrieval.ContextSource;
import io.groundedrag.retrieval.Embedder;
import io.groundedrag.retrieval.HybridRetriever;
import io.groundedrag.retrieval.RetrievalResult;
import io.groundedrag.security.Caller;
import io.groundedrag.security.SpaceAuthorizer;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The knowledge base as MCP tools (streamable HTTP at {@code /mcp}), so agents such as Claude
 * Desktop, Claude Code or any MCP client can search and ask it.
 *
 * <p>The same security as the REST API: the MCP endpoint sits behind the API-key / JWT filter
 * chain, every tool resolves the authenticated {@link Caller}, and spaces are authorised exactly
 * as in {@code /api/v1/chat}. An agent can never reach a space its key was not granted.
 */
@Component
public class KnowledgeBaseTools {

    public record Passage(int number, String source, String section, Integer page, String space, double score,
            String text) {
    }

    public record SearchResult(String confidence, List<Passage> passages) {
    }

    public record Citation(int number, String source, String section, Integer page) {
    }

    public record Answer(String answer, String confidence, boolean verified, String notice, List<Citation> citations,
            String answerId) {
    }

    private final Embedder embedder;
    private final HybridRetriever retriever;
    private final ContextAssembler assembler;
    private final RagService rag;

    public KnowledgeBaseTools(Embedder embedder, HybridRetriever retriever, ContextAssembler assembler,
            RagService rag) {
        this.embedder = embedder;
        this.retriever = retriever;
        this.assembler = assembler;
        this.rag = rag;
    }

    @McpTool(name = "search_documents", description = """
            Search the user's document spaces and return the most relevant passages with their source file, \
            section and page. Use it to find evidence; cite passages by source when you use them. \
            Returns no passages when nothing relevant exists.""")
    public SearchResult searchDocuments(
            @McpToolParam(description = "What to look for, as a natural-language question or keywords") String query,
            @McpToolParam(description = "Spaces to search; omit to search every space this key can read",
                    required = false) List<String> spaces) {
        Set<SpaceId> allowed = SpaceAuthorizer.resolve(caller(), spaces);
        RetrievalResult r = retriever.retrieve(query, embedder.embed(query), allowed);
        List<ContextSource> sources = switch (r.confidence()) {
            case NONE -> List.of();
            default -> assembler.assemble(r.chunks());
        };
        return new SearchResult(r.confidence().name(), sources.stream().map(KnowledgeBaseTools::passage).toList());
    }

    @McpTool(name = "ask_documents", description = """
            Answer a question from the user's documents. The answer cites numbered sources, is verified \
            against them (verified=false means some figure or citation did not check out), and says so \
            when the documents do not cover the question.""")
    public Answer askDocuments(
            @McpToolParam(description = "The question") String question,
            @McpToolParam(description = "Spaces to search; omit to search every space this key can read",
                    required = false) List<String> spaces,
            @McpToolParam(description = "Pass the same id across calls to ask follow-up questions",
                    required = false) String conversationId) {
        Caller caller = caller();
        ChatAnswer a = rag.ask(new RagService.Question(caller.name(), SpaceAuthorizer.resolve(caller, spaces),
                question, conversationId));
        return new Answer(a.answer(), a.confidence().level().name(),
                a.verification() != null && a.verification().grounded(), a.notice(),
                a.sources().stream().filter(ChatAnswer.Source::cited)
                        .map(s -> new Citation(s.number(), s.sourceName(), s.section(), s.page())).toList(),
                a.answerId().toString());
    }

    private static Passage passage(ContextSource s) {
        RetrievedChunk c = s.chunk();
        return new Passage(s.number(), c.sourceName(), c.section(), c.page(), c.space().value(),
                Math.round(c.rerankScore() * 1000) / 1000.0, s.text());
    }

    private static Caller caller() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Caller caller)) {
            throw new AccessDeniedException("Not authenticated");
        }
        return caller;
    }
}
