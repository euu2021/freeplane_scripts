// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// Discussion thread: https://github.com/freeplane/freeplane/issues/2999
// Version: 1.0

/***
 * Appends a reversed copy of a chain of nodes to the end of that chain, so the result reads
 * the same in both directions:
 *
 *   A - B - C - D - E          ->     A - B - C - D - E - E - D - C - B - A
 *                                                         \___ copies ___/
 *
 * Which chain:
 *   - select its first and its last node (Ctrl+click), or all of its nodes: the chain goes
 *     from the selected node closest to the root down to the deepest selected node. All
 *     selected nodes must be on the same line of descent;
 *   - with a single node selected, the chain goes from the root of the map down to it.
 *
 * The original nodes are not touched. Each copy is a full copy of the corresponding node
 * (text, formatting, icons, attributes, details, note), without its other children. The
 * first copy is selected at the end, so the whole reversed part can be cut and pasted
 * somewhere else right away. The run is a single undo step.
 *
 * Some adjustments keep the copies meaningful in their new place:
 *   - a formula is replaced by its current value, because references such as
 *     "=parent.text" would point to a different node in the reversed part;
 *   - the copy of the map root loses the root style, a copy of a free node is not free, and
 *     a copy of a summary node is a plain node (it no longer has siblings to summarize).
 */

import org.freeplane.api.Node
import org.freeplane.features.map.SummaryNodeFlag

List<Node> chain = selectedChain()
if (chain == null) {
    c.statusInfo = 'Append reversed path: the selected nodes are not on a single line of descent'
    return
}

Node parent = chain.last()
parent.folded = false
Node firstCopy = null
chain.reverse().each { Node original ->
    Node copy = parent.appendChild(original)
    adjustCopy(copy, original)
    if (firstCopy == null)
        firstCopy = copy
    parent = copy
}
c.select(firstCopy)
c.statusInfo = ('Append reversed path: ' + chain.size() + ' node(s) copied').toString()

List<Node> selectedChain() {
    List<Node> selected = c.selecteds as List<Node>
    Node deepest = selected.max { it.pathToRoot.size() }
    List<Node> path = deepest.pathToRoot
    if (selected.size() == 1)
        return path
    List<String> pathIds = path*.id
    if (!selected.every { pathIds.contains(it.id) })
        return null
    Node topmost = selected.min { it.pathToRoot.size() }
    return path.subList(pathIds.indexOf(topmost.id), path.size())
}

void adjustCopy(Node copy, Node original) {
    if (original.text?.startsWith('='))
        copy.text = original.transformedText
    if (original.isRoot())
        copy.style.name = null
    if (copy.free)
        copy.free = false
    // The public API has no way to read or clear the summary flag. Removing it directly is
    // safe here: the node was just created, and undoing the run removes it altogether.
    copy.delegate.removeExtension(SummaryNodeFlag.class)
}
