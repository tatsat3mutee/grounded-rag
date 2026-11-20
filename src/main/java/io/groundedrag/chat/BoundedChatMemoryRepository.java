package io.groundedrag.chat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;

/**
 * In-process {@link ChatMemoryRepository} with LRU eviction over conversations. Spring AI's
 * {@code InMemoryChatMemoryRepository} never evicts, which under real traffic is a slow memory
 * leak; this one keeps at most {@code maxConversations}.
 */
public class BoundedChatMemoryRepository implements ChatMemoryRepository {

    private final Map<String, List<Message>> conversations;

    public BoundedChatMemoryRepository(int maxConversations) {
        this.conversations = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<Message>> eldest) {
                return size() > maxConversations;
            }
        };
    }

    @Override
    public synchronized List<String> findConversationIds() {
        return List.copyOf(conversations.keySet());
    }

    @Override
    public synchronized List<Message> findByConversationId(String conversationId) {
        List<Message> messages = conversations.get(conversationId);
        return messages == null ? List.of() : List.copyOf(messages);
    }

    @Override
    public synchronized void saveAll(String conversationId, List<Message> messages) {
        conversations.put(conversationId, List.copyOf(messages));
    }

    @Override
    public synchronized void deleteByConversationId(String conversationId) {
        conversations.remove(conversationId);
    }
}
