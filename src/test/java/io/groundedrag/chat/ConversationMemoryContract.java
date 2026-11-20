package io.groundedrag.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import io.groundedrag.generation.Turn;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;

/** Conversation memory behaviour on any Spring AI ChatMemoryRepository (in-process and JDBC). */
public abstract class ConversationMemoryContract {

    protected abstract ChatMemoryRepository repository();

    private ConversationMemory memory(int maxTurns) {
        return new ConversationMemory(MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository()).maxMessages(maxTurns * 2).build());
    }

    private final String conversation = UUID.randomUUID().toString();

    @Test
    void remembersTurnsInOrder() {
        ConversationMemory m = memory(6);
        m.append("alice", conversation, new Turn("What is the refund window?", "30 days [1]."));
        m.append("alice", conversation, new Turn("And for monthly plans?", "Not refundable [1]."));
        assertThat(m.history("alice", conversation)).containsExactly(
                new Turn("What is the refund window?", "30 days [1]."),
                new Turn("And for monthly plans?", "Not refundable [1]."));
    }

    @Test
    void sameConversationIdUnderAnotherCallerIsAnotherConversation() {
        ConversationMemory m = memory(6);
        m.append("alice", conversation, new Turn("secret question", "secret answer"));
        assertThat(m.history("mallory", conversation)).isEmpty();
    }

    @Test
    void keepsOnlyTheLastTurnsInTheWindow() {
        ConversationMemory m = memory(2);
        for (int i = 1; i <= 4; i++) {
            m.append("alice", conversation, new Turn("q" + i, "a" + i));
        }
        assertThat(m.history("alice", conversation)).extracting(Turn::question).containsExactly("q3", "q4");
    }

    @Test
    void survivesANewMemoryInstanceOverTheSameRepository() {
        memory(6).append("alice", conversation, new Turn("q", "a"));
        assertThat(memory(6).history("alice", conversation)).containsExactly(new Turn("q", "a"));
    }

    @Test
    void keyFitsTheRepositoryColumn() {
        assertThat(ConversationMemory.key("a-very-long-principal-name|" + "x".repeat(200), conversation)).hasSize(36);
    }
}
