// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// Discussion thread: https://sourceforge.net/p/freeplane/discussion/758437/thread/e9200db6c3/?limit=25#b2c8
// Version: 1.0

/**
 * Keeps the map compact: every node whose text is longer than
 * `max_shortened_text_length` is shown minimized (shortened), whatever created it.
 *
 * This is an evolution of the NodeChangeListener script macmarrum posted in the
 * "Request: Max node height" thread (see the Discussion thread link above), which
 * minimized a node as soon as its text got too long. That version misses every node
 * that is born with its text already set: `MindMap.addListener` is fed only by node
 * CHANGE events, and pasting a branch, importing one, creating nodes through the AI
 * plugin or calling `node.createChild(text)` all build the node with the text inside
 * and only then insert it -- no text-change event ever happens. The symptom is a
 * listener that works while you type and does nothing when content arrives ready-made.
 *
 * So this version listens one layer lower, on the ModeController's own listener lists:
 *
 *   - INodeChangeListener   -> text edited by hand (NodeModel.NODE_TEXT)
 *   - IMapChangeListener    -> onNodeInserted: paste, import, AI, undo of a deletion
 *   - IMapLifeCycleListener -> a map that is opened or reloaded is swept once
 *
 * `onNodeInserted` fires only for the top of an inserted branch, so the whole subtree
 * is walked. All three lists live on the ModeController, not on the MapModel, so the
 * listeners survive a map reload and cover every open map by themselves.
 *
 * The sweep of a map being opened only minimizes, never expands: a short node that was
 * minimized by hand is a decision of yours, and opening a map is no reason to undo it.
 *
 * The work is deferred to the end of the current event cycle (invokeLater) and batched,
 * so the model is never modified while the insertion event is still being delivered.
 *
 * Minimizing is applied WITHOUT an undo step (UNDOABLE = false): being minimized is a
 * derived state, recomputable from the text at any time, and an undo step of its own
 * would mean two Ctrl+Z to undo one paste -- the first one only expanding the nodes
 * again. Set UNDOABLE = true to go through MTextController instead.
 *
 * Run the script to install the listeners, run it again to reinstall them (the old
 * instances are removed first, so nothing is registered twice). They live until
 * Freeplane is closed; bind it to your startup scripts to have it always on.
 *
 * This replaces `paste_and_minimize.groovy`, which only covered pasting.
 */

import org.freeplane.core.undo.IUndoHandler
import org.freeplane.core.util.HtmlUtils
import org.freeplane.features.map.IMapChangeListener
import org.freeplane.features.map.IMapLifeCycleListener
import org.freeplane.features.map.INodeChangeListener
import org.freeplane.features.map.MapController
import org.freeplane.features.map.MapModel
import org.freeplane.features.map.NodeChangeEvent
import org.freeplane.features.map.NodeModel
import org.freeplane.features.mode.Controller as CoreController
import org.freeplane.features.text.ShortenedTextModel
import org.freeplane.features.text.mindmapmode.MTextController
import org.freeplane.plugin.script.FreeplaneScriptBaseClass.ConfigProperties

import javax.swing.SwingUtilities

class AutoMinimizer implements INodeChangeListener, IMapChangeListener, IMapLifeCycleListener {
    /** true = a node whose text got short again is expanded back. */
    static final boolean UNMINIMIZE_SHORT_TEXT = true
    /** true = a node inserted right under the root is pushed to the right side. */
    static final boolean SIDE_AT_ROOT = true
    /** true = each batch becomes an undo step of its own (see the header). */
    static final boolean UNDOABLE = false
    /** true = a map that is opened or reloaded is swept once (minimize only). */
    static final boolean SWEEP_ON_OPEN = true

    /** node -> may this node be expanded again if its text is short? */
    private final LinkedHashMap<NodeModel, Boolean> pending = new LinkedHashMap<NodeModel, Boolean>()
    private final LinkedHashSet<NodeModel> pendingSide = new LinkedHashSet<NodeModel>()
    private boolean flushScheduled = false

    // --- events ----------------------------------------------------------------------

    @Override
    void nodeChanged(NodeChangeEvent event) {
        if (event.property == NodeModel.NODE_TEXT) {
            schedule(event.node, UNMINIMIZE_SHORT_TEXT)
        }
    }

    @Override
    void onNodeInserted(NodeModel parent, NodeModel child, int index) {
        scheduleSubtree(child, UNMINIMIZE_SHORT_TEXT)
        if (SIDE_AT_ROOT && parent.isRoot() && child.side != NodeModel.Side.BOTTOM_OR_RIGHT) {
            pendingSide.add(child)
        }
    }

    @Override
    void onCreate(MapModel map) {
        if (SWEEP_ON_OPEN && map.rootNode != null) {
            scheduleSubtree(map.rootNode, false)
        }
    }

    // --- batching --------------------------------------------------------------------

    void scheduleSubtree(NodeModel node, boolean mayExpand) {
        schedule(node, mayExpand)
        node.children.each { scheduleSubtree(it, mayExpand) }
    }

    private void schedule(NodeModel node, boolean mayExpand) {
        MapModel map = node.map
        if (map == null || map.class.simpleName == 'StyleMapModel') {
            return
        }
        // an undo that re-inserts nodes must not be "corrected" back
        IUndoHandler undo = map.getExtension(IUndoHandler.class)
        if (undo != null && undo.isUndoActionRunning()) {
            return
        }
        pending[node] = mayExpand || pending[node]
        if (!flushScheduled) {
            flushScheduled = true
            SwingUtilities.invokeLater { flush() }
        }
    }

    private void flush() {
        flushScheduled = false
        LinkedHashMap<NodeModel, Boolean> nodes = new LinkedHashMap<NodeModel, Boolean>(pending)
        List<NodeModel> sides = new ArrayList<NodeModel>(pendingSide)
        pending.clear()
        pendingSide.clear()

        int maxLength = new ConfigProperties().getIntProperty('max_shortened_text_length')
        MapController mapController = CoreController.currentModeController.mapController

        nodes.keySet().groupBy { it.map }.each { MapModel map, List<NodeModel> mapNodes ->
            IUndoHandler undo = UNDOABLE ? map.getExtension(IUndoHandler.class) : null
            undo?.startTransaction()
            try {
                mapNodes.each { NodeModel node ->
                    if (!isAlive(node)) {
                        return
                    }
                    boolean tooLong = plainText(node).length() > maxLength
                    if (tooLong) {
                        setMinimized(mapController, node, true)
                    }
                    else if (nodes[node]) {
                        setMinimized(mapController, node, false)
                    }
                }
                sides.findAll { it.map.is(map) && isAlive(it) }.each { NodeModel node ->
                    mapController.setSide([node], NodeModel.Side.BOTTOM_OR_RIGHT)
                }
            }
            finally {
                undo?.commit()
            }
        }
    }

    private static void setMinimized(MapController mapController, NodeModel node, boolean state) {
        if ((ShortenedTextModel.getShortenedTextModel(node) != null) == state) {
            return
        }
        if (UNDOABLE) {
            ((MTextController) MTextController.getController()).setIsMinimized(node, state)
            return
        }
        if (state) {
            ShortenedTextModel.createShortenedTextModel(node)
        }
        else {
            node.removeExtension(ShortenedTextModel.class)
        }
        mapController.nodeChanged(node, ShortenedTextModel.SHORTENER, !state, state)
    }

    private static boolean isAlive(NodeModel node) {
        node.map != null && (node.isRoot() || node.parentNode != null)
    }

    private static String plainText(NodeModel node) {
        // the raw text on purpose: evaluating a formula from inside a listener is not safe
        String text = node.text
        text == null ? '' : HtmlUtils.htmlToPlain(text)
    }
}

// scripts are recompiled on every run, so the old class and the new one are distinct
// Class objects: uninstall by class name, not by type
def mapController = CoreController.currentModeController.mapController
mapController.mapChangeListeners
        .findAll { it.class.simpleName == AutoMinimizer.simpleName }
        .each { mapController.removeMapChangeListener(it) }
mapController.nodeChangeListeners
        .findAll { it.class.simpleName == AutoMinimizer.simpleName }
        .each { mapController.removeNodeChangeListener(it) }
mapController.mapLifeCycleListeners
        .findAll { it.class.simpleName == AutoMinimizer.simpleName }
        .each { mapController.removeMapLifeCycleListener(it) }

def minimizer = new AutoMinimizer()
mapController.addMapChangeListener(minimizer)
mapController.addNodeChangeListener(minimizer)
mapController.addMapLifeCycleListener(minimizer)

// maps that were already open when the script ran get their sweep now
if (AutoMinimizer.SWEEP_ON_OPEN) {
    c.openMindMaps.each { minimizer.scheduleSubtree(it.root.delegate, false) }
}

c.statusInfo = 'Auto minimizer installed.'
