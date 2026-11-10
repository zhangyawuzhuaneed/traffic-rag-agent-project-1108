package com.example.tunnel.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.vectorstore.VectorStore;
// import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;



@RestController
public class ChatController {

    private final ChatClient chatClient;
    private final DemoTools demoTools;
    private final ChatMemory chatMemory;
    private final VectorStore vectorStore;

    public ChatController(ChatClient.Builder builder, DemoTools demoTools, ChatMemory chatMemory, VectorStore vectorStore) {
        this.demoTools = demoTools;
        this.chatMemory = chatMemory;
        this.vectorStore = vectorStore;
        // this.chatClient = builder
        //     .defaultTools(demoTools)
        //     .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
        //     .build();

        this.chatClient = builder
        .defaultTools(demoTools)
        // .defaultAdvisors(
        //         MessageChatMemoryAdvisor.builder(chatMemory).build(),  // 对话记忆增强
        //         QuestionAnswerAdvisor.builder(vectorStore).build()     // RAG 检索增强
        // )
        .build();
    }


    @GetMapping ("/chat")
    public String chat(@RequestParam String sessionId, @RequestParam String question ) { 
        var memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
        var qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore).build();

    return chatClient.prompt()
            .user(question)
            .advisors(memoryAdvisor, qaAdvisor)
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
            .call()
            .content();
    }



    @GetMapping ("/demo")
    public String demo(@RequestParam String question) { 
        return chatClient.prompt()
            .user(question)
            .call()
            .content();
    }


    // ✅ SSE 标准媒体类型：text/event-stream
    @GetMapping(value = "/stream", produces = "text/html;charset=UTF-8")
    public Flux<String> stream(@RequestParam String question) {
        return chatClient.prompt()
                .user(question)
                .stream() // 重点：stream 而不是 call
                .content();
    }


    /**
     * 这是一本书
     * Entity
     */
    public  static  class Entity { 
        private String content; 
        private Integer price;

        public Entity(  String content,    Integer price) { 
            this.price = price;
            this.content = content;
        }

        public Integer getPrice() {
            return price;
        }

        public void setPrice(Integer price) {
            this.price = price;
        }
        public String getContent() { 
            return content; 
        } 
        public void setContent(String content) { 
            this.content = content; 
        } 
    }

    @GetMapping(value = "/entityFlux")
    public Entity entityFlux(@RequestParam String question) {
        return chatClient.prompt()
                .user("推荐一本关于" + question + "的书，返回大概内容和价格两个标签")
                .call() // 重点：stream 而不是 call
                .entity(Entity.class);
    }


    @GetMapping("/agent")
    public String agent(@RequestParam String message) {
        return chatClient.prompt()
                .user(message)
                .call()
                .content();
    }



    @GetMapping("/memory")
    public String memory(@RequestParam String sessionId, @RequestParam String message) {
        return chatClient.prompt()
                .user(message)
                // 关键：传入当前会话ID，隔离不同用户的记忆
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();
    }


  
}