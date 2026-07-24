// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 2 of the License, or
// (at your option) any later version.

/**
 * Lorem ipsum node generator.
 *
 * Grows a random subtree below the selected node until a target node count is
 * reached (1000 by default). Every generated node carries 1..40 lorem ipsum
 * words, and every node that gets expanded receives 1..10 children. Nodes still
 * waiting in the frontier when the target is reached stay leaves, so the target
 * count is met exactly instead of being overshot.
 *
 * Word counts are not uniform: about 85% of the nodes stay in 1..10 words and
 * the rest thins out towards 40, so a generated map looks like a real one
 * instead of a wall of text (measured mean: 7.2 words per node).
 *
 * The frontier node to expand is picked at random, which yields an irregular
 * tree (mixed depths and branch sizes) rather than the perfectly layered shape a
 * breadth-first walk would produce.
 *
 * Meant for building large maps to test performance, folding, filtering, etc.
 *
 * Undo: when launched from the menu or a shortcut, the whole generation is a
 * single undo step, because ExecuteScriptAction already wraps script execution
 * in one transaction.
 *
 * Speed: node creation on a map with an open view costs about 8 ms per node
 * (measured, and linear up to 4000 nodes), so 1000 nodes take roughly 8 seconds
 * with a frozen UI. That cost belongs to the view update, not to this script,
 * hence the confirmation prompt above CONFIRMATION_THRESHOLD nodes.
 */

import javax.swing.JOptionPane
import org.freeplane.api.Node
import org.freeplane.core.ui.components.UITools

// --------------------------------------------------------------- defaults

int defaultNodeCount() { return 1000 }

int minWordsPerNode() { return 1 }

int maxWordsPerNode() { return 40 }

/** Most nodes stay at or below this word count. */
int shortWordLimit() { return 10 }

/** Share of nodes allowed to grow past shortWordLimit. */
double longTextProbability() { return 0.15d }

/**
 * Chance of adding one more word once a node went past shortWordLimit, so the
 * long tail thins out instead of being flat up to maxWordsPerNode.
 */
double tailGrowthProbability() { return 0.85d }

int minChildrenPerNode() { return 1 }

int maxChildrenPerNode() { return 10 }

/** Above this node count the user is warned about the expected waiting time. */
int confirmationThreshold() { return 2000 }

/** Measured on a map with an open view; used only for the warning message. */
double millisPerNodeEstimate() { return 8.0d }

// --------------------------------------------------------------- text

List<String> loremVocabulary() {
    return [
        'lorem', 'ipsum', 'dolor', 'sit', 'amet', 'consectetur', 'adipiscing', 'elit',
        'sed', 'do', 'eiusmod', 'tempor', 'incididunt', 'ut', 'labore', 'et', 'dolore',
        'magna', 'aliqua', 'enim', 'ad', 'minim', 'veniam', 'quis', 'nostrud',
        'exercitation', 'ullamco', 'laboris', 'nisi', 'aliquip', 'ex', 'ea', 'commodo',
        'consequat', 'duis', 'aute', 'irure', 'in', 'reprehenderit', 'voluptate',
        'velit', 'esse', 'cillum', 'eu', 'fugiat', 'nulla', 'pariatur', 'excepteur',
        'sint', 'occaecat', 'cupidatat', 'non', 'proident', 'sunt', 'culpa', 'qui',
        'officia', 'deserunt', 'mollit', 'anim', 'id', 'est', 'laborum', 'at', 'vero',
        'eos', 'accusamus', 'iusto', 'odio', 'dignissimos', 'ducimus', 'blanditiis',
        'praesentium', 'voluptatum', 'deleniti', 'atque', 'corrupti', 'quos', 'quas',
        'molestias', 'excepturi', 'obcaecati', 'cupiditate', 'similique', 'mollitia',
        'animi', 'dolorum', 'fuga', 'harum', 'quidem', 'rerum', 'facilis', 'expedita',
        'distinctio', 'nam', 'libero', 'tempore', 'soluta', 'nobis', 'eligendi',
        'optio', 'cumque', 'nihil', 'impedit', 'quo', 'minus', 'maxime', 'placeat',
        'facere', 'possimus', 'omnis', 'assumenda', 'repellendus', 'temporibus',
        'autem', 'quibusdam', 'officiis', 'debitis', 'necessitatibus', 'saepe',
        'eveniet', 'voluptates', 'repudiandae', 'recusandae', 'itaque', 'earum',
        'hic', 'tenetur', 'sapiente', 'delectus', 'reiciendis', 'voluptatibus',
        'maiores', 'alias', 'perferendis', 'doloribus', 'asperiores', 'repellat'
    ]
}

/**
 * Draws a word count in minWords..maxWords, skewed towards short texts: most
 * draws land uniformly in minWords..shortWordLimit, and only longTextProbability
 * of them go past that, each extra word getting less likely than the previous
 * one. Uniform over the whole range would make almost every node a wall of text.
 */
int randomWordCount(Random random, int minWords, int maxWords) {
    int shortLimit = Math.min(shortWordLimit(), maxWords)
    if (shortLimit < minWords) {
        shortLimit = minWords
    }
    boolean goesLong = (maxWords > shortLimit) && (random.nextDouble() < longTextProbability())
    if (!goesLong) {
        return minWords + random.nextInt(shortLimit - minWords + 1)
    }
    int wordCount = shortLimit + 1
    while (wordCount < maxWords && random.nextDouble() < tailGrowthProbability()) {
        wordCount++
    }
    return wordCount
}

/** Builds a text of minWords..maxWords random words, first one capitalized. */
String loremText(Random random, List<String> vocabulary, int minWords, int maxWords) {
    int wordCount = randomWordCount(random, minWords, maxWords)
    StringBuilder text = new StringBuilder()
    for (int i = 0; i < wordCount; i++) {
        if (i > 0) {
            text.append(' ')
        }
        text.append(vocabulary.get(random.nextInt(vocabulary.size())))
    }
    text.setCharAt(0, Character.toUpperCase(text.charAt(0)))
    return text.toString()
}

// --------------------------------------------------------------- generation

/**
 * Creates exactly nodeCount descendants below seed and returns a report map
 * with created, maxDepth (relative to seed), leaves and millis.
 *
 * randomSeed non-null makes the shape and the texts reproducible.
 */
Map generateLoremTree(Node seed, int nodeCount, Long randomSeed = null,
                      int minWords = minWordsPerNode(), int maxWords = maxWordsPerNode(),
                      int minChildren = minChildrenPerNode(), int maxChildren = maxChildrenPerNode()) {
    if (nodeCount <= 0) {
        return [created: 0, maxDepth: 0, leaves: 0, millis: 0]
    }
    Random random = (randomSeed == null) ? new Random() : new Random(randomSeed.longValue())
    List<String> vocabulary = loremVocabulary()
    long startedAt = System.nanoTime()

    // frontier entries are [node, depth]; the pick is random and the removal is a
    // swap with the last element, so order does not matter and removal stays O(1)
    List<Object[]> frontier = new ArrayList<Object[]>()
    frontier.add([seed, Integer.valueOf(0)] as Object[])
    int created = 0
    int expanded = 0
    int maxDepth = 0

    while (created < nodeCount && !frontier.isEmpty()) {
        int pick = random.nextInt(frontier.size())
        Object[] entry = frontier.get(pick)
        int lastIndex = frontier.size() - 1
        frontier.set(pick, frontier.get(lastIndex))
        frontier.remove(lastIndex)

        Node parent = (Node) entry[0]
        int depth = ((Integer) entry[1]).intValue()
        int childCount = minChildren + random.nextInt(maxChildren - minChildren + 1)
        expanded++
        for (int i = 0; i < childCount && created < nodeCount; i++) {
            Node child = parent.createChild(loremText(random, vocabulary, minWords, maxWords))
            created++
            int childDepth = depth + 1
            if (childDepth > maxDepth) {
                maxDepth = childDepth
            }
            frontier.add([child, Integer.valueOf(childDepth)] as Object[])
        }
    }
    return [created: created,
            maxDepth: maxDepth,
            leaves: frontier.size(),
            millis: (int) ((System.nanoTime() - startedAt) / 1000000L)]
}

// --------------------------------------------------------------- entry point

String answer = JOptionPane.showInputDialog(UITools.getCurrentFrame(),
        'How many lorem ipsum nodes should be created below the selected node?',
        String.valueOf(defaultNodeCount()))
if (answer == null) {
    return
}

int requestedCount
try {
    requestedCount = Integer.parseInt(answer.trim())
}
catch (NumberFormatException ignored) {
    requestedCount = -1
}
if (requestedCount <= 0) {
    UITools.informationMessage('Please enter a positive number of nodes.')
    return
}

if (requestedCount > confirmationThreshold()) {
    int expectedSeconds = (int) Math.ceil(requestedCount * millisPerNodeEstimate() / 1000.0d)
    int choice = JOptionPane.showConfirmDialog(UITools.getCurrentFrame(),
            'Creating ' + requestedCount + ' nodes takes around ' + expectedSeconds
                    + ' seconds, and Freeplane stays unresponsive until it is done.\nGo ahead?',
            'Lorem ipsum generator', JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
    if (choice != JOptionPane.OK_OPTION) {
        return
    }
}

Map report = generateLoremTree(node, requestedCount)
c.statusInfo = ('Lorem ipsum: ' + report.created + ' nodes, max depth ' + report.maxDepth
        + ', ' + report.leaves + ' leaves, ' + report.millis + ' ms').toString()
