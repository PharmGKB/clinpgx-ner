package org.clinpgx;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A trie keyed on token sequences, used to find the longest dictionary entry starting at a position in tokenized text.
 * Lookup cost depends on the length of the match, not on the number of entries.
 */
class TokenTrie<V> {
    record Match<V>(int length, V value) {}

    private static final class Node<V> {
        // created on first child; most nodes are leaves
        Map<String, Node<V>> children;
        V value;
    }

    private final Node<V> root = new Node<>();
    private int size;

    /**
     * Adds an entry. If the key is already present the existing value is kept and {@code false} is returned.
     */
    boolean put(List<String> tokens, V value) {
        Node<V> node = root;
        for (String token : tokens) {
            if (node.children == null) {
                node.children = new HashMap<>(4);
            }
            node = node.children.computeIfAbsent(token, t -> new Node<>());
        }
        if (node.value != null) {
            return false;
        }
        node.value = value;
        size++;
        return true;
    }

    /**
     * Returns the longest entry matching {@code tokens} starting at {@code start}, or {@code null} if none does.
     */
    Match<V> longestMatch(List<String> tokens, int start) {
        Node<V> node = root;
        Match<V> best = null;
        for (int i = start; i < tokens.size() && node.children != null; i++) {
            node = node.children.get(tokens.get(i));
            if (node == null) {
                break;
            }
            if (node.value != null) {
                best = new Match<>(i - start + 1, node.value);
            }
        }
        return best;
    }

    int size() {
        return size;
    }
}
