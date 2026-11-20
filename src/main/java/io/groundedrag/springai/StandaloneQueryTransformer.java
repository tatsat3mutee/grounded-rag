package io.groundedrag.springai;

import java.util.List;

import io.groundedrag.generation.QueryRewriter;
import io.groundedrag.generation.Turn;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

/**
 * This service's follow-up rewriting as a Spring AI {@link QueryTransformer}: the role
 * {@code CompressionQueryTransformer} plays in Spring AI's modular RAG, but backed by the same
 * rewriter the API uses (LLM-based, or the offline heuristic), so both paths behave alike.
 */
public class StandaloneQueryTransformer implements QueryTransformer {

    private final QueryRewriter rewriter;

    public StandaloneQueryTransformer(QueryRewriter rewriter) {
        this.rewriter = rewriter;
    }

    @Override
    public Query transform(Query query) {
        List<Turn> turns = Turn.fromMessages(query.history());
        String standalone = rewriter.standalone(turns, query.text());
        return query.mutate().text(standalone).build();
    }
}
