package org.booklore.service.metadata.smart;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentResponseJsonExtractorTest {

    @Test
    void returnsPlainObjectUnchanged() {
        assertThat(AgentResponseJsonExtractor.extractObject("{\"a\":1}")).contains("{\"a\":1}");
    }

    @Test
    void findsObjectInsideMarkdownFence() {
        String response = """
                Here is the result:
                ```json
                {"original_title": "Journal de voyage"}
                ```
                """;
        assertThat(AgentResponseJsonExtractor.extractObject(response))
                .contains("{\"original_title\": \"Journal de voyage\"}");
    }

    // Merged stderr means the JSON is routinely preceded by progress chatter that itself contains
    // braces; the scan has to survive that rather than latch onto the first '{' it sees.
    @Test
    void skipsUnbalancedNoiseBeforeTheObject() {
        String response = "tool { search started\n{\"a\":1}";
        assertThat(AgentResponseJsonExtractor.extractObject(response)).contains("{\"a\":1}");
    }

    @Test
    void keepsBracesThatAppearInsideStrings() {
        String response = "{\"description\": \"a } brace and a { one\"}";
        assertThat(AgentResponseJsonExtractor.extractObject(response)).contains(response);
    }

    @Test
    void keepsEscapedQuotesInsideStrings() {
        String response = "{\"description\": \"he said \\\"no\\\" once\"}";
        assertThat(AgentResponseJsonExtractor.extractObject(response)).contains(response);
    }

    @Test
    void handlesNestedObjects() {
        String response = "prefix {\"outer\": {\"inner\": 1}} suffix";
        assertThat(AgentResponseJsonExtractor.extractObject(response)).contains("{\"outer\": {\"inner\": 1}}");
    }

    @Test
    void returnsEmptyWhenNothingBalances() {
        assertThat(AgentResponseJsonExtractor.extractObject("{\"a\": 1")).isEmpty();
    }

    @Test
    void returnsEmptyForBlankOrNullInput() {
        assertThat(AgentResponseJsonExtractor.extractObject(null)).isEmpty();
        assertThat(AgentResponseJsonExtractor.extractObject("   ")).isEmpty();
    }
}
