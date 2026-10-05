package org.clinpgx;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TokenTrieTest {

    @Test
    void testLongestMatchPrefersLongerEntry() {
        TokenTrie<String> trie = new TokenTrie<>();
        trie.put(List.of("CYP2C19"), "gene");
        trie.put(List.of("CYP2C19", "*", "2"), "allele");

        TokenTrie.Match<String> match = trie.longestMatch(List.of("CYP2C19", "*", "2", "carriers"), 0);

        assertEquals(3, match.length());
        assertEquals("allele", match.value());
    }

    @Test
    void testLongestMatchFallsBackToShorterEntry() {
        TokenTrie<String> trie = new TokenTrie<>();
        trie.put(List.of("CYP2C19"), "gene");
        trie.put(List.of("CYP2C19", "*", "2"), "allele");

        // "CYP2C19 *" is a prefix of the allele, but not an entry itself
        TokenTrie.Match<String> match = trie.longestMatch(List.of("CYP2C19", "*", "17"), 0);

        assertEquals(1, match.length());
        assertEquals("gene", match.value());
    }

    @Test
    void testLongestMatchStartsAtGivenPosition() {
        TokenTrie<String> trie = new TokenTrie<>();
        trie.put(List.of("warfarin"), "drug");

        assertNull(trie.longestMatch(List.of("took", "warfarin"), 0));
        assertEquals(1, trie.longestMatch(List.of("took", "warfarin"), 1).length());
    }

    @Test
    void testPutKeepsFirstValueForDuplicateKey() {
        TokenTrie<String> trie = new TokenTrie<>();

        assertTrue(trie.put(List.of("HLA", "-", "B"), "first"));
        assertFalse(trie.put(List.of("HLA", "-", "B"), "second"));
        assertEquals("first", trie.longestMatch(List.of("HLA", "-", "B"), 0).value());
        assertEquals(1, trie.size());
    }
}
