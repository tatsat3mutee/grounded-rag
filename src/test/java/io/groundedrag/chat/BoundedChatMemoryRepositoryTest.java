package io.groundedrag.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.UserMessage;

class BoundedChatMemoryRepositoryTest extends ConversationMemoryContract {

    private final BoundedChatMemoryRepository repository = new BoundedChatMemoryRepository(1000);

    @Override
    protected ChatMemoryRepository repository() {
        return repository;
    }

    @Test
    void evictsTheLeastRecentlyUsedConversation() {
        BoundedChatMemoryRepository small = new BoundedChatMemoryRepository(2);
        small.saveAll("a", List.of(new UserMessage("1")));
        small.saveAll("b", List.of(new UserMessage("2")));
        small.findByConversationId("a"); // touch a
        small.saveAll("c", List.of(new UserMessage("3")));
        assertThat(small.findConversationIds()).containsExactlyInAnyOrder("a", "c");
    }
}
