// Copyright (c) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 2 of the License, or
// (at your option) any later version.

/***
 * Visual effects for map editing actions.
 *
 * Companion of SoundEffects.groovy: instead of playing a sound, it draws a short
 * animation at the place where the action happened. Covered:
 *
 *     select        -> a light mark on the node that just took the selection
 *                      (styles, see SELECT_STYLE)
 *     create        -> a ring expands from the new node and fades out
 *     move          -> the node's trip from old place to new (styles, see MOVE_STYLE)
 *     delete        -> the node leaving, drawn entirely from geometry captured before
 *                      the deletion (styles, see DELETE_STYLE)
 *     fold / unfold -> the branch collapsing into the node or growing out of it
 *                      (styles, see FOLD_STYLE)
 *
 * Running the script again switches the effects off (toggle), so a single keyboard
 * shortcut turns the whole thing on and off.
 *
 * HOW IT WORKS
 * ------------
 * The animation is painted by a transparent JPanel hung on the MapViewScrollPane of
 * the tab, exactly like the connection lines of UtilityPanels.groovy. The panel is
 * mouse-transparent and reserves no viewport area, so it changes nothing about the
 * way the map behaves - it only paints.
 *
 * Geometry is resolved on EVERY frame rather than captured once, so an effect keeps
 * sitting on its node while the map moves, scrolls or relayouts under it. Two actions
 * are the exception, because the place they act on is gone by the time they fire:
 * a moved node's ORIGIN and everything about a DELETED node are measured in the
 * pre-event (onPreNodeMoved / onPreNodeDelete), in map coordinates so they survive
 * scrolling, and the effect then draws from those fixed rectangles.
 */

import java.awt.AWTEvent
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Composite
import java.awt.Component
import java.awt.Container
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.AWTEventListenerProxy
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.QuadCurve2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage

import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager

import org.freeplane.features.map.IMapChangeListener
import org.freeplane.features.map.INodeSelectionListener
import org.freeplane.features.map.MapModel
import org.freeplane.features.map.NodeDeletionEvent
import org.freeplane.features.map.NodeModel
import org.freeplane.features.map.NodeMoveEvent
import org.freeplane.features.mode.Controller
import org.freeplane.view.swing.map.MapView
import org.freeplane.view.swing.map.MapViewScrollPane
import org.freeplane.view.swing.map.NodeView

// ---------------------------------------------------------------------------
// configuration
// ---------------------------------------------------------------------------

final String INSTALL_KEY = 'VisualEffects.teardown'

final boolean EFFECT_ON_CREATE = true
final boolean EFFECT_ON_MOVE = true
final boolean EFFECT_ON_DELETE = true

// 'ripple' - a flash on the node, rings rippling outwards, sparks flying off (the original)
// 'pop'    - the outline springs up from nothing, overshoots, and settles (a bounce)
// 'burst'  - no rings: a dense burst of sparks and dots shooting out of the node
// 'halo'   - a single soft glow swells around the node once and fades (the quiet one)
final String CREATE_STYLE = 'ripple'

// Pasting a branch fires ONE insertion - for the top of the pasted subtree - so the style
// above only ever marks that node. With this on, a rectangle additionally grows from it to
// cover the whole new branch: the exact inverse of the delete sweep. Costs nothing on a
// plain new node, where the subtree is just the node itself and nothing extra is drawn.
final boolean CREATE_SWEEP_BRANCH = true

// 'trail' - a comet tail is dragged from the old place to the new one
// 'ghost' - a translucent copy of the node slides over to the new place
// 'comet' - the sliding copy, dragging a tail behind it
// 'arc'   - a curved line is drawn between the two places, with a head running along it
// 'pulse' - a ring implodes where the node was and one expands where it landed
final String MOVE_STYLE = 'arc'

// Where the 'arc' starts. The other styles always use the old SLOT, because they depict the
// node itself travelling; the arc depicts the relationship, so it has a choice.
//
// 'slot'   - the spot the node used to occupy. Honest about pixels, but the map rearranges
//            around the move, so that spot can end up meaning nothing.
// 'parent' - the node's PREVIOUS PARENT, at wherever it sits after the rearrangement. Both
//            ends of the arc are then live nodes, so nothing can go stale, and it reads as
//            "this came from there" rather than "this was at these coordinates".
// 'auto'   - 'parent' when the move changed parents, 'slot' when it was a reorder inside the
//            same parent (where an arc from the parent says nothing about what happened).
final String ARC_ORIGIN = 'auto'

final boolean EFFECT_ON_FOLD = true
final boolean EFFECT_ON_UNFOLD = true

// 'ripple'   - rings, closing in on the node when it folds, opening when it unfolds
// 'sweep'    - the area the children occupy shrinks into the node / grows out of it
// 'chevrons' - three chevrons on the children's side, pointing in or out
// 'spokes'   - one line from the node to each direct child, lit in sequence
final String FOLD_STYLE = 'sweep'

// 'implode'  - the node shrinks into its own centre, with a ring falling in after it
// 'shatter'  - the node breaks into pieces that fly apart and drop
// 'sweep'    - the whole branch that went away collapses into the place it left
// 'ripple'   - the sweep, plus the create ripple's flash, rings and sparks fired from the
//              spot the node vacated: the same visual vocabulary as a creation, run in the
//              delete colour, so the two actions rhyme. The rings close INWARDS here, so
//              they converge with the sweep instead of crossing it.
// 'sparks'   - the same, without the rings: sweep + flash + sparks only
final String DELETE_STYLE = 'sparks'

// Which node the delete effect hangs from once the map has relaid out. The deleted branch is
// gone by the time anything is painted, so its position has to be reconstructed from a node
// that is still there - and the two candidates disagree whenever the deletion changes the
// layout, which is most of the time.
// 'parent' - the effect stays glued to the node the branch hung from. It follows the parent
//            when the relayout moves it, which is what the eye expects: the ghost belongs to
//            that node.
// 'root'   - the effect stays at the spot the branch occupied on screen just before it went.
//            Historically exact, and the root really is the fixed point (measured: 0 px on
//            screen while the viewport compensates the layout). The catch is that everything
//            BETWEEN the root and the deletion does move, so on a deep map the ghost is left
//            behind, detached from the parent by however far the parent travelled.
final String DELETE_ANCHOR = 'parent'

// ---- how long a sweep lasts, wherever it appears ---------------------------------------
// There are two, one per direction, and every action defers to them - so a nudge here moves
// the unfold, the paste, the fold and the delete together. Both are longer than the movement
// itself because every sweep holds (SWEEP_OPEN_TRAVEL / SWEEP_CLOSE_HOLD): the surplus is the
// pause, not a faster animation.
final int SWEEP_OPEN_DURATION_MS = 820     // unfold, paste
final int SWEEP_CLOSE_DURATION_MS = 800    // fold, delete

// A closing sweep can additionally outline every node that stood inside the branch, so the
// shape of what is going is visible before it goes. Off = the rectangles are never captured
// either, so it costs exactly nothing.
final boolean SWEEP_SKELETON = true

final boolean EFFECT_ON_SELECT = true

// Selection is by far the most frequent action - it fires on every arrow key - so these are
// deliberately the quietest and shortest effects here.
// 'halo'    - a soft glow swells around the node once and fades (the create halo, quicker)
// 'corners' - four brackets snap onto the node's corners, like a camera focusing
// 'ring'    - a single ring falls inwards and lands on the node
final String SELECT_STYLE = 'halo'
final int SELECT_DURATION_MS = 260
// Held arrow keys fire selections faster than any animation can finish. Below this distance
// the effect is simply not started again - the running one is left to play out.
final int SELECT_THROTTLE_MS = 90
// Moving or deleting reselects a neighbour BEFORE the edit event arrives, so without this a
// structural edit would always flash a selection on the wrong node first.
final int SELECT_MUTE_AFTER_EDIT_MS = 350
final Color SELECT_COLOR = null

final int CREATE_DURATION_MS = 520   // the create RIPPLE; a pasted branch's sweep uses the
                                     // shared opening length above, on its own clock
final int MOVE_DURATION_MS = 900
// these exist so an action can be broken out of the shared timing if it ever needs to
final int DELETE_DURATION_MS = SWEEP_CLOSE_DURATION_MS
final int FOLD_DURATION_MS = SWEEP_CLOSE_DURATION_MS
// Folding reaches no listener at all, so it is detected by comparing snapshots of the
// visible node views shortly after each input. Lower = snappier, but more scans.
final int FOLD_POLL_DELAY_MS = 60
// a structural edit reshapes the tree too: keep the two effects from firing on top of
// each other
final int FOLD_MUTE_AFTER_EDIT_MS = 450
final int FRAME_MS = 16          // ~60 fps
final int GROW_PX = 26           // how far the outer ring travels past the node
final int RINGS = 2              // 2 rings give the ripple feel; 1 is drier
final boolean SPARKS = true      // short radial strokes leaving a created node
final int SPARK_COUNT = 10
// null = derived from the map background (a strong colour on light maps, a bright
// one on dark maps). Set a Color here to force it.
final Color CREATE_COLOR = null
final Color MOVE_COLOR = null
final Color FOLD_COLOR = null
// delete reads better in a warning colour; null falls back to the shared default
final Color DELETE_COLOR = new Color(255, 90, 70)

// ---------------------------------------------------------------------------
// geometry: the two coordinate systems that matter
// ---------------------------------------------------------------------------

class VisualFxGeometry {
    // The node's content rectangle in MAP coordinates. Map coordinates are what a captured
    // position must be stored in: they do not move when the view scrolls, while overlay
    // coordinates do.
    static Rectangle contentRectInMap(MapView mapView, NodeModel node) {
        NodeView view = visibleViewOf(mapView, node)
        if (view == null) {
            return null
        }
        // getContent() is public and inert, and is the very component that
        // getNodeContentLocation measured - getContentPane() is private and mutates the view
        JComponent content = view.getContent()
        if (content == null || content.getWidth() <= 0) {
            return null
        }
        Point location = mapView.getNodeContentLocation(view)
        return new Rectangle((int) location.getX(), (int) location.getY(),
                content.getWidth(), content.getHeight())
    }

    // Everything the node's branch covers on screen, in MAP coordinates. Used by the delete
    // effect, which has to know the whole extent BEFORE the deletion - afterwards there is
    // no view left to measure.
    static Rectangle subtreeRectInMap(MapView mapView, NodeModel node) {
        Rectangle box = contentRectInMap(mapView, node)
        if (node.isFolded()) {
            return box
        }
        for (NodeModel child : node.getChildren()) {
            Rectangle childBox = subtreeRectInMap(mapView, child)
            if (childBox != null) {
                box = box == null ? childBox : box.union(childBox)
            }
        }
        return box
    }

    // The node itself, or the outermost folded ancestor standing in for it
    static NodeView visibleViewOf(MapView mapView, NodeModel node) {
        NodeView view = mapView.getNodeView(node)
        if (view != null) {
            return view
        }
        NodeModel ancestor = node.getParentNode()
        while (ancestor != null) {
            view = mapView.getNodeView(ancestor)
            if (view != null) {
                return view
            }
            ancestor = ancestor.getParentNode()
        }
        return null
    }
}

// ---------------------------------------------------------------------------
// folding: nobody is told about it, so it has to be watched
//
// NodeModel.setFolded only notifies the views - neither IMapChangeListener nor
// INodeChangeListener ever hears about a fold. The way out is to walk the tree of
// NODE VIEWS (which exists only for what is on screen) right after each user input
// and compare it with the previous walk: a node that just entered the folded set
// folded, one that left it unfolded. The same walk collects the geometry, because a
// folded node's children are gone by the time the fold is noticed - the area they
// used to occupy is only knowable from the PREVIOUS snapshot.
// ---------------------------------------------------------------------------

@groovy.transform.CompileStatic
class VisualFxFoldScanner {
    // Walks the visible node views, filling `folded` with the models of folded nodes and
    // `geometry` with [subtree box, direct children boxes, own box, from, to] for expanded
    // nodes near the viewport. Returns the subtree box of this view, in MAP coordinates.
    //
    // Coordinates are accumulated on the way down (each node view is a child component of
    // its parent's view), which keeps the whole walk at one addition per node - no
    // convertPoint call per node.
    //
    // `flat` receives EVERY visited rectangle, once, in DFS order. That order is what makes
    // the skeleton affordable: a node's subtree is a CONTIGUOUS range of that list, so each
    // candidate only has to remember two ints instead of a copy of its descendants. Keeping a
    // per-node list would cost O(nodes x depth); this is O(nodes) for the whole scan.
    static Rectangle scan(NodeView view, int offsetX, int offsetY, Rectangle viewportInMap,
            Set<NodeModel> folded, Map<NodeModel, Object[]> geometry, List<Rectangle> flat) {
        int x = offsetX + view.getX()
        int y = offsetY + view.getY()
        JComponent content = view.getContent()
        Rectangle own = content == null ? null :
                new Rectangle(x + content.getX(), y + content.getY(),
                        content.getWidth(), content.getHeight())
        int from = flat.size()
        if (own != null) {
            flat.add(own)
        }
        Rectangle box = own
        if (view.isFolded()) {
            folded.add(view.getNode())
            return box
        }
        List<NodeView> children = view.getChildrenViews()
        List<Rectangle> childBoxes = new ArrayList<Rectangle>()
        for (NodeView child : children) {
            Rectangle childBox = scan(child, x, y, viewportInMap, folded, geometry, flat)
            if (childBox == null) {
                continue
            }
            box = box == null ? new Rectangle(childBox) : box.union(childBox)
            JComponent childContent = child.getContent()
            if (childContent != null) {
                childBoxes.add(new Rectangle(x + child.getX() + childContent.getX(),
                        y + child.getY() + childContent.getY(),
                        childContent.getWidth(), childContent.getHeight()))
            }
        }
        // Remember only what the user can actually fold: keeping a rectangle per node of a
        // 25 000 node map would cost far more memory than the effect is worth.
        //
        // The node's OWN rectangle is kept alongside the subtree box, because the effect
        // needs the two as an offset, not as absolute map coordinates: folding relayouts
        // the map and moves every node, so an absolute rectangle captured before the fold
        // points somewhere else afterwards.
        if (!childBoxes.isEmpty() && box != null && box.intersects(viewportInMap)) {
            geometry.put(view.getNode(), [box, childBoxes, own, from, flat.size()] as Object[])
        }
        return box
    }
}

// ---------------------------------------------------------------------------
// one running effect
// ---------------------------------------------------------------------------

class VisualFxPulse {
    String kind          // 'create', 'move', 'delete', 'fold' or 'unfold'
    String style         // per-action style; ignored by 'create' when it has none
    NodeModel node       // the anchor: the node itself, or the parent for a deleted one
    // Every edit relayouts the map, so where a captured rectangle should be pinned is a
    // question with a MEASURED answer, not an obvious one - and it differs per action:
    //
    //   fold/unfold : offset from `node` (the folded node). It re-centres against the
    //                 children that just vanished and moves a lot - measured 339 px, enough
    //                 to put a branch that was above the node below it.
    //   delete      : offset from `node` (the parent). Measured across four shapes of
    //                 deletion: 0-1 px off, against 15-28 px for absolute coordinates.
    //   move        : ABSOLUTE map coordinates, on purpose. Anchoring the origin to the old
    //                 parent is WORSE (26 px off vs 2 px), because that parent re-centres
    //                 when it loses a child while the screen position barely moves.
    Rectangle areaOffset
    List<Rectangle> childrenOffset
    // fold/delete: one rectangle per node that was inside the branch, so the sweep can show
    // the shape of what is about to go. Offsets, like everything else here.
    List<Rectangle> skeletonOffsets
    // the same outlines pre-rendered once, for when there are too many to stroke per frame;
    // `skeletonImage` is positioned at the node plus `skeletonImageAt`
    BufferedImage skeletonImage
    Rectangle skeletonImageAt
    boolean skeletonImageTried
    boolean sweepBranch      // create: also sweep the whole subtree the node brought with it
    Rectangle originOffset   // delete: the deleted node's rect, relative to the parent
    Rectangle originInMap    // move: where the node came from, in map coordinates
    // move + arc: when set, the arc starts at THIS node's live position instead of the slot
    // above, which sidesteps staleness entirely - both ends are then resolved every frame
    NodeModel originAnchor
    long startedAt
    // The pulse lives for durationMs, but its STYLE may be a shorter animation riding inside
    // it. That is what lets a pasted branch keep the create ripple exactly as it was (520 ms)
    // while its sweep runs on the same clock as every other opening sweep (820 ms). For every
    // other action the two are equal and this costs nothing.
    int durationMs
    int styleDurationMs
    int growPx
    int rings
    boolean sparks
    int sparkCount
    Color color
    long seed

    private static double clamp(long elapsed, int span) {
        double t = (double) elapsed / (double) span
        return t < 0d ? 0d : (t > 1d ? 1d : t)
    }

    // the whole pulse, which is also the sweep's clock
    double progress(long now) {
        return clamp(now - startedAt, durationMs)
    }

    // the style's own clock: flash, rings, sparks, pop, halo
    double styleProgress(long now) {
        return clamp(now - startedAt, styleDurationMs > 0 ? styleDurationMs : durationMs)
    }
}

// ---------------------------------------------------------------------------
// the overlay: one per map view, created on demand, paints every running effect
// ---------------------------------------------------------------------------

class VisualFxOverlay extends JPanel {
    static final String OVERLAY_NAME = 'freeplane-visual-effects-overlay'
    // fraction of the move animation the 'arc' style stays at full strength before fading
    static final double ARC_HOLD = 0.55d
    // ---- the sweep, shared by unfold, fold, delete and paste ------------------------------
    // Tuning any of these changes EVERY sweep at once, which is the point of there being one.
    // fraction of an opening sweep spent growing; the rest is it resting on the branch, fading
    static final double SWEEP_OPEN_TRAVEL = 0.55d
    // fraction of a closing sweep spent resting on the branch before it starts collapsing
    static final double SWEEP_CLOSE_HOLD = 0.4d
    static final double SWEEP_FILL_ALPHA = 45d
    static final double SWEEP_EDGE_ALPHA = 200d
    static final float SWEEP_EDGE_WIDTH = 2f
    // how far outside the node the 'corners' brackets come to rest
    static final double SELECT_CORNER_REST = 5d
    // the skeleton: outlines of the nodes that were inside a branch being folded or deleted
    static final double SKELETON_ALPHA = 120d
    static final float SKELETON_EDGE_WIDTH = 1.2f
    // A hard ceiling on how many outlines are ever captured. Measured on live maps, a fold
    // draws 13-23 outlines in the median case and up to ~230 for a big open branch.
    static final int SKELETON_MAX = 400
    // Above this many outlines, stroke them ONCE into an image and blit that every frame.
    // Measured at 45 us per stroked outline, so the per-frame cost grows without bound; a
    // blit does not. Below the threshold the blit is the slower of the two, hence the switch.
    //   250 outlines: 13.2 ms/frame stroked -> 3.1 ms/frame blitted
    //    30 outlines:  1.7 ms/frame stroked -> 1.4 ms/frame blitted (plus 2.2 ms to build)
    static final int SKELETON_CACHE_MIN = 40

    private final MapView mapView
    private final List<VisualFxPulse> pulses = new ArrayList<VisualFxPulse>()
    private final Timer timer

    VisualFxOverlay(MapView mapView, int frameMs) {
        this.mapView = mapView
        setName(OVERLAY_NAME)
        setOpaque(false)
        setFocusable(false)
        this.timer = new Timer(frameMs, { ActionEvent event -> tick() } as ActionListener)
    }

    // The overlay covers the whole scroll pane, so it must not swallow the clicks meant
    // for the map. A component with no mouse listener is already skipped by the Swing
    // dispatcher, but saying it explicitly keeps that guarantee independent of what gets
    // added to the panel later.
    @Override
    boolean contains(int x, int y) {
        return false
    }

    void add(VisualFxPulse pulse) {
        syncBounds()
        // A selection supersedes the previous one - arrow-key navigation would otherwise pile
        // up a dozen overlapping effects, each still repainting its own region.
        if ('select' == pulse.kind) {
            for (Iterator<VisualFxPulse> it = pulses.iterator(); it.hasNext();) {
                VisualFxPulse old = it.next()
                if ('select' == old.kind) {
                    repaintPulse(old)
                    it.remove()
                }
            }
        }
        pulses.add(pulse)
        if (!timer.isRunning()) {
            timer.start()
        }
        repaintPulse(pulse)
    }

    // Follow the scroll pane instead of listening to it: a component listener on a target
    // that outlives this panel is exactly the kind of leftover that piles up across runs,
    // and there is nothing to animate while no effect is running anyway.
    void syncBounds() {
        Container host = getParent()
        if (host == null) {
            return
        }
        if (getX() != 0 || getY() != 0 || getWidth() != host.getWidth() || getHeight() != host.getHeight()) {
            setBounds(0, 0, host.getWidth(), host.getHeight())
        }
    }

    void stop() {
        timer.stop()
        pulses.clear()
    }

    private void tick() {
        if (getParent() == null || !isShowing()) {
            // the tab was closed or hidden: nothing to animate against
            pulses.clear()
            timer.stop()
            return
        }
        syncBounds()
        long now = System.currentTimeMillis()
        List<VisualFxPulse> finished = new ArrayList<VisualFxPulse>()
        for (VisualFxPulse pulse : pulses) {
            repaintPulse(pulse)
            if (pulse.progress(now) >= 1d) {
                finished.add(pulse)
            }
        }
        pulses.removeAll(finished)
        if (pulses.isEmpty()) {
            timer.stop()
        }
    }

    private void repaintPulse(VisualFxPulse pulse) {
        Rectangle anchor = nodeBounds(pulse.node)
        Rectangle dirty = anchor
        // resolve the pasted branch here too, not only while painting: the dirty region has to
        // already cover it on the first frame it becomes measurable, or that frame is clipped
        if (pulse.sweepBranch && pulse.areaOffset == null && anchor != null) {
            branchOffsetOf(pulse)
        }
        for (Rectangle extra : [atNode(anchor, pulse.areaOffset),
                                atNode(anchor, pulse.originOffset),
                                pulse.originInMap == null ? null : toOverlay(pulse.originInMap),
                                // the arc can start at the previous parent, which may sit
                                // well outside the node/slot pair - without it in the dirty
                                // region the curve gets clipped or leaves a trail
                                nodeBounds(pulse.originAnchor)]) {
            if (extra != null) {
                dirty = dirty == null ? extra : dirty.union(extra)
            }
        }
        if (dirty == null) {
            return
        }
        int margin = pulse.growPx + 10
        // Rectangle fields read as double in Groovy (the Rectangle2D getters win over the
        // int fields), and repaint(double, double, double, double) does not exist
        repaint((int) (dirty.getX() - margin), (int) (dirty.getY() - margin),
                (int) (dirty.getWidth() + 2 * margin), (int) (dirty.getHeight() + 2 * margin))
    }

    // A map rectangle in this panel's coordinates. convertPointToAncestor is NOT usable
    // here: it walks up from the source and would run past this panel (a sibling of the
    // viewport, not an ancestor of the map view) all the way to the screen.
    Rectangle toOverlay(Rectangle inMap) {
        if (inMap == null) {
            return null
        }
        Point location = SwingUtilities.convertPoint(mapView, (int) inMap.getX(), (int) inMap.getY(), this)
        return new Rectangle((int) location.getX(), (int) location.getY(),
                (int) inMap.getWidth(), (int) inMap.getHeight())
    }

    // The branch a freshly created node brought with it, as an offset from the node itself.
    //
    // Unlike delete and fold, a creation can still be MEASURED - the nodes are on screen. It
    // is measured once, on the first frame where the views exist (they may not yet exist when
    // the insertion is broadcast), and then kept as an offset so a relayout during the
    // animation cannot make it stale. Returns null while it cannot be resolved, and when the
    // subtree is just the node itself, which is the ordinary "new empty node" case.
    Rectangle branchOffsetOf(VisualFxPulse pulse) {
        if (pulse.areaOffset != null) {
            return pulse.areaOffset
        }
        Rectangle own = VisualFxGeometry.contentRectInMap(mapView, pulse.node)
        Rectangle subtree = VisualFxGeometry.subtreeRectInMap(mapView, pulse.node)
        if (own == null || subtree == null) {
            return null
        }
        if (subtree.getWidth() <= own.getWidth() + 2d && subtree.getHeight() <= own.getHeight() + 2d) {
            return null
        }
        pulse.areaOffset = new Rectangle((int) (subtree.getX() - own.getX()),
                (int) (subtree.getY() - own.getY()),
                (int) subtree.getWidth(), (int) subtree.getHeight())
        return pulse.areaOffset
    }

    // An offset captured relative to the node, placed against wherever the node is NOW.
    static Rectangle atNode(Rectangle node, Rectangle offset) {
        if (node == null || offset == null) {
            return null
        }
        // every read below goes through the Rectangle2D getters and comes back as double
        return new Rectangle((int) (node.getX() + offset.getX()), (int) (node.getY() + offset.getY()),
                (int) offset.getWidth(), (int) offset.getHeight())
    }

    // Where the node is right now, in this panel's coordinates; null when it has no view
    // (hidden inside a folded ancestor) and no visible ancestor either.
    Rectangle nodeBounds(NodeModel node) {
        if (node == null || getParent() == null) {
            return null
        }
        return toOverlay(VisualFxGeometry.contentRectInMap(mapView, node))
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g)
        if (pulses.isEmpty()) {
            return
        }
        long now = System.currentTimeMillis()
        Graphics2D g2 = (Graphics2D) g.create()
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            // keep the effect inside the viewport: it must not paint over the scroll bars
            Rectangle viewport = getParent() instanceof MapViewScrollPane ?
                    ((MapViewScrollPane) getParent()).getViewport().getBounds() : null
            if (viewport != null) {
                g2.clip(viewport)
            }
            for (VisualFxPulse pulse : new ArrayList<VisualFxPulse>(pulses)) {
                if ('move' == pulse.kind) {
                    paintMove(g2, pulse, now)
                }
                else if ('fold' == pulse.kind || 'unfold' == pulse.kind) {
                    paintFolding(g2, pulse, now)
                }
                else if ('delete' == pulse.kind) {
                    paintDelete(g2, pulse, now)
                }
                else if ('select' == pulse.kind) {
                    paintSelect(g2, pulse, now)
                }
                else {
                    paintCreate(g2, pulse, now)
                }
            }
        }
        finally {
            g2.dispose()
        }
    }

    // -----------------------------------------------------------------------
    // create: something appearing where the new node is
    // -----------------------------------------------------------------------

    private void paintCreate(Graphics2D g2, VisualFxPulse pulse, long now) {
        Rectangle area = nodeBounds(pulse.node)
        if (area == null) {
            return
        }
        // two clocks: the sweep runs the full pulse, the style runs its own shorter one
        double t = pulse.styleProgress(now)
        int red = pulse.color.getRed()
        int green = pulse.color.getGreen()
        int blue = pulse.color.getBlue()

        // the pasted branch, opening out of the node - the delete sweep run backwards. Painted
        // first so the style's own marks stay on top of it.
        if (pulse.sweepBranch) {
            Rectangle branch = atNode(area, branchOffsetOf(pulse))
            if (branch != null) {
                // a paste is an opening sweep, exactly like an unfold - same function, same
                // hold, same duration
                paintSweep(g2, area, branch, pulse.progress(now), true, red, green, blue)
            }
        }

        if ('pop' == pulse.style) {
            paintCreatePop(g2, pulse, area, t, red, green, blue)
        }
        else if ('burst' == pulse.style) {
            paintFlash(g2, area, t, red, green, blue)
            paintSparks(g2, pulse, area, t, red, green, blue, 1.4d)
            paintDots(g2, pulse, area, t, red, green, blue)
        }
        else if ('halo' == pulse.style) {
            paintCreateHalo(g2, pulse, area, t, red, green, blue)
        }
        else {
            paintFlash(g2, area, t, red, green, blue)
            paintRings(g2, pulse, area, t, red, green, blue, false)
            if (pulse.sparks) {
                paintSparks(g2, pulse, area, t, red, green, blue, 1d)
            }
        }
    }

    // -----------------------------------------------------------------------
    // select: the lightest effects here, because this one fires constantly
    // -----------------------------------------------------------------------

    private void paintSelect(Graphics2D g2, VisualFxPulse pulse, long now) {
        Rectangle area = nodeBounds(pulse.node)
        if (area == null) {
            return
        }
        double t = pulse.progress(now)
        int red = pulse.color.getRed()
        int green = pulse.color.getGreen()
        int blue = pulse.color.getBlue()

        if ('corners' == pulse.style) {
            paintSelectCorners(g2, pulse, area, t, red, green, blue)
        }
        else if ('ring' == pulse.style) {
            paintSelectRing(g2, pulse, area, t, red, green, blue)
        }
        else {
            // the create halo, on a shorter clock: one breath around the node
            paintCreateHalo(g2, pulse, area, t, red, green, blue)
        }
    }

    // four brackets that fly in and settle on the corners, like a camera focusing
    private void paintSelectCorners(Graphics2D g2, VisualFxPulse pulse, Rectangle area, double t,
            int red, int green, int blue) {
        double close = 1d - Math.pow(1d - t, 3d)
        // They settle a few pixels OUTSIDE the node, never on its outline: Freeplane already
        // draws a strong selection border there, and brackets landing on it simply vanish.
        double away = SELECT_CORNER_REST + pulse.growPx * 0.65d * (1d - close)
        // fade in fast, out slow, so the arrival is what the eye catches
        double fade = t < 0.25d ? t / 0.25d : Math.pow(1d - (t - 0.25d) / 0.75d, 1.4d)
        int alpha = (int) Math.round(fade * 230d)
        if (alpha <= 3) {
            return
        }
        double arm = Math.max(6d, Math.min(18d, Math.min(area.width, area.height) * 0.35d))
        g2.setColor(new Color(red, green, blue, alpha))
        g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND))
        double left = area.x - away, right = area.x + area.width + away
        double top = area.y - away, bottom = area.y + area.height + away
        for (int corner = 0; corner < 4; corner++) {
            double cx = (corner == 0 || corner == 2) ? left : right
            double cy = corner < 2 ? top : bottom
            double dx = (corner == 0 || corner == 2) ? arm : -arm
            double dy = corner < 2 ? arm : -arm
            g2.draw(new Line2D.Double(cx, cy, cx + dx, cy))
            g2.draw(new Line2D.Double(cx, cy, cx, cy + dy))
        }
    }

    // one ring falling inwards, landing on the node
    private void paintSelectRing(Graphics2D g2, VisualFxPulse pulse, Rectangle area, double t,
            int red, int green, int blue) {
        double spread = pulse.growPx * 0.8d * Math.pow(1d - t, 1.6d)
        double fade = t < 0.2d ? t / 0.2d : Math.pow(1d - (t - 0.2d) / 0.8d, 1.2d)
        int alpha = (int) Math.round(fade * 225d)
        if (alpha <= 3) {
            return
        }
        g2.setColor(new Color(red, green, blue, alpha))
        g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND))
        double arc = 12d + spread
        g2.draw(new RoundRectangle2D.Double(area.x - spread, area.y - spread,
                area.width + 2d * spread, area.height + 2d * spread, arc, arc))
    }

    // -----------------------------------------------------------------------
    // shared building blocks: the pieces the 'ripple' look is made of. Used by the create
    // ripple and by the delete ripple, which is why they take a plain rectangle rather than
    // reading the node - a deleted node has none.
    // -----------------------------------------------------------------------

    // the rectangle lights up and fades quickly. Kept light on purpose: it paints OVER the
    // node, and a strong flash hides the text the user just typed.
    private void paintFlash(Graphics2D g2, Rectangle area, double t, int red, int green, int blue) {
        double flash = Math.pow(1d - t, 2.6d) * 0.26d
        if (flash > 0.01d) {
            g2.setColor(new Color(red, green, blue, (int) Math.round(flash * 255d)))
            g2.fill(new RoundRectangle2D.Double(area.x - 2d, area.y - 2d,
                    area.width + 4d, area.height + 4d, 12d, 12d))
        }
    }

    // Rings that ripple outwards from the rectangle, each starting a bit later so they chase
    // each other - or, with `inward`, the same rings closing ONTO it.
    //
    // Direction is not decoration: an expanding ring next to the delete sweep, which is
    // contracting at the same time, gives two large rounded rectangles crossing each other
    // and reads as noise. Everything converging on the vacated spot reads as one event.
    private void paintRings(Graphics2D g2, VisualFxPulse pulse, Rectangle area, double t,
            int red, int green, int blue, boolean inward) {
        for (int ring = 0; ring < pulse.rings; ring++) {
            double delay = ring * 0.16d
            if (t <= delay) {
                continue
            }
            double ringT = Math.min(1d, (t - delay) / (1d - delay))
            double ringFade = Math.pow(1d - ringT, 1.8d) * (ring == 0 ? 1d : 0.6d)
            double spread = inward ? pulse.growPx * Math.pow(1d - ringT, 1.1d)
                                   : pulse.growPx * (1d - Math.pow(1d - ringT, 3d))
            int alpha = (int) Math.round(ringFade * 235d)
            if (alpha <= 3) {
                continue
            }
            g2.setColor(new Color(red, green, blue, alpha))
            g2.setStroke(new BasicStroke((float) (1d + 2.4d * ringFade),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND))
            double arc = 14d + spread
            g2.draw(new RoundRectangle2D.Double(area.x - spread, area.y - spread,
                    area.width + 2d * spread, area.height + 2d * spread, arc, arc))
        }
    }

    // short radial strokes that shoot out of the node's edge and die
    private void paintSparks(Graphics2D g2, VisualFxPulse pulse, Rectangle area, double t,
            int red, int green, int blue, double strength) {
        if (t >= 0.85d) {
            return
        }
        double eased = 1d - Math.pow(1d - t, 3d)
        double sparkFade = Math.pow(1d - t / 0.85d, 1.5d)
        int alpha = (int) Math.round(sparkFade * 220d)
        if (alpha <= 3) {
            return
        }
        g2.setColor(new Color(red, green, blue, alpha))
        g2.setStroke(new BasicStroke((float) (1d + 1.4d * sparkFade * strength),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND))
        Random random = new Random(pulse.seed)
        double centerX = area.x + area.width / 2d
        double centerY = area.y + area.height / 2d
        double halfWidth = area.width / 2d
        double halfHeight = area.height / 2d
        for (int i = 0; i < pulse.sparkCount; i++) {
            double angle = 2d * Math.PI * i / pulse.sparkCount + (random.nextDouble() - 0.5d) * 0.35d
            double reach = pulse.growPx * strength * (0.75d + random.nextDouble() * 0.75d)
            double cos = Math.cos(angle)
            double sin = Math.sin(angle)
            // leave from the node's RECTANGLE, not from an inscribed ellipse: on a wide node the
            // ellipse bunches every spark up against the two short sides
            double toEdge = Math.min(
                    Math.abs(cos) < 1e-6d ? Double.MAX_VALUE : halfWidth / Math.abs(cos),
                    Math.abs(sin) < 1e-6d ? Double.MAX_VALUE : halfHeight / Math.abs(sin))
            double fromX = centerX + cos * (toEdge + 3d)
            double fromY = centerY + sin * (toEdge + 3d)
            double travel = reach * eased
            double length = 5d + 5d * sparkFade
            g2.draw(new Line2D.Double(fromX + cos * travel, fromY + sin * travel,
                    fromX + cos * (travel + length), fromY + sin * (travel + length)))
        }
    }

    // small dots flung outward, the confetti half of the burst
    private void paintDots(Graphics2D g2, VisualFxPulse pulse, Rectangle area, double t,
            int red, int green, int blue) {
        if (t >= 0.9d) {
            return
        }
        double eased = 1d - Math.pow(1d - t, 2.4d)
        double fade = Math.pow(1d - t / 0.9d, 1.5d)
        Random random = new Random(pulse.seed * 31L + 7L)
        double centerX = area.x + area.width / 2d
        double centerY = area.y + area.height / 2d
        int dots = pulse.sparkCount + 6
        for (int i = 0; i < dots; i++) {
            double angle = random.nextDouble() * 2d * Math.PI
            double reach = pulse.growPx * (0.9d + random.nextDouble() * 1.3d)
            double travel = reach * eased
            double radius = (1.4d + random.nextDouble() * 2.2d) * fade
            int alpha = (int) Math.round(fade * 235d)
            if (alpha <= 3 || radius <= 0.3d) {
                continue
            }
            double x = centerX + Math.cos(angle) * travel
            double y = centerY + Math.sin(angle) * travel
            g2.setColor(new Color(red, green, blue, alpha))
            g2.fill(new Ellipse2D.Double(x - radius, y - radius, radius * 2d, radius * 2d))
        }
    }

    // the outline springs up from a smaller size, overshoots, and settles - a bounce
    private void paintCreatePop(Graphics2D g2, VisualFxPulse pulse, Rectangle area, double t,
            int red, int green, int blue) {
        // a damped spring: overshoots 1.0 once around t=0.5, then settles back to it
        double scale = 1d + Math.sin(t * Math.PI * 1.6d) * 0.16d * Math.pow(1d - t, 1.2d)
        double fade = Math.pow(1d - t, 1.1d)
        double centerX = area.x + area.width / 2d
        double centerY = area.y + area.height / 2d
        double width = area.width * scale
        double height = area.height * scale
        double x = centerX - width / 2d
        double y = centerY - height / 2d

        g2.setColor(new Color(red, green, blue, (int) Math.round(fade * 55d)))
        g2.fill(new RoundRectangle2D.Double(x, y, width, height, 12d, 12d))
        g2.setColor(new Color(red, green, blue, (int) Math.round(fade * 235d)))
        g2.setStroke(new BasicStroke((float) (1.5d + 1.5d * fade),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND))
        g2.draw(new RoundRectangle2D.Double(x, y, width, height, 12d, 12d))
    }

    // one soft glow that swells around the node and fades - the quiet option
    private void paintCreateHalo(Graphics2D g2, VisualFxPulse pulse, Rectangle area, double t,
            int red, int green, int blue) {
        double grow = 1d - Math.pow(1d - t, 2d)
        double reach = Math.max(pulse.growPx * 0.7d, Math.min(area.width, area.height) * 0.4d) * grow
        // fade in fast, out slow: a breath around the node
        double fade = Math.sin(Math.min(1d, t) * Math.PI)
        int layers = 4
        for (int i = layers; i >= 1; i--) {
            double spread = reach * i / (double) layers
            int alpha = (int) Math.round(fade * 42d * (1d - (i - 1) / (double) layers))
            if (alpha <= 2) {
                continue
            }
            g2.setColor(new Color(red, green, blue, alpha))
            double arc = 16d + spread
            g2.fill(new RoundRectangle2D.Double(area.x - spread, area.y - spread,
                    area.width + 2d * spread, area.height + 2d * spread, arc, arc))
        }
    }

    // -----------------------------------------------------------------------
    // move: from where the node was to where it is now
    // -----------------------------------------------------------------------

    private void paintMove(Graphics2D g2, VisualFxPulse pulse, long now) {
        Rectangle to = nodeBounds(pulse.node)
        // Absolute, and measured to be the better choice for depicting the old SLOT: the old
        // parent re-centres when it loses a child, so pinning the slot to it drifts 26 px
        // where absolute drifts 2 px.
        Rectangle from = toOverlay(pulse.originInMap)
        if (pulse.originAnchor != null) {
            // ...but the arc can skip the slot altogether and start at the previous parent,
            // resolved live. Nothing to go stale, at the cost of pointing at the parent
            // rather than at the exact spot the node vacated.
            Rectangle anchored = nodeBounds(pulse.originAnchor)
            if (anchored != null) {
                from = anchored
            }
        }
        if (to == null || from == null) {
            return
        }
        double t = pulse.progress(now)
        double eased = 1d - Math.pow(1d - t, 2.6d)
        int red = pulse.color.getRed()
        int green = pulse.color.getGreen()
        int blue = pulse.color.getBlue()

        double fromX = from.x + from.width / 2d
        double fromY = from.y + from.height / 2d
        double toX = to.x + to.width / 2d
        double toY = to.y + to.height / 2d

        if ('ghost' == pulse.style || 'comet' == pulse.style) {
            paintGhost(g2, pulse, t, eased, from, to, red, green, blue)
        }
        else if ('arc' == pulse.style) {
            paintArc(g2, pulse, t, eased, fromX, fromY, toX, toY, to, red, green, blue)
        }
        else if ('pulse' == pulse.style) {
            paintImplodeExplode(g2, pulse, t, from, to, red, green, blue)
        }
        else {
            paintTrail(g2, pulse, t, eased, from, to, fromX, fromY, toX, toY, red, green, blue)
        }
    }

    // a comet: a thick head running to the new place, dragging a tail that thins out
    private void paintTrail(Graphics2D g2, VisualFxPulse pulse, double t, double eased,
            Rectangle from, Rectangle to, double fromX, double fromY, double toX, double toY,
            int red, int green, int blue) {
        double fade = Math.pow(1d - t, 1.4d)

        // the node's outline stays behind for a moment where it used to be
        double ghostFade = Math.pow(1d - Math.min(1d, t / 0.55d), 1.6d)
        if (ghostFade > 0.02d) {
            g2.setColor(new Color(red, green, blue, (int) Math.round(ghostFade * 150d)))
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    1f, [3f, 4f] as float[], 0f))
            g2.draw(new RoundRectangle2D.Double(from.x, from.y, from.width, from.height, 12d, 12d))
        }

        paintTail(g2, t, eased, from, to, fromX, fromY, toX, toY, red, green, blue, 1d)

        // arriving: the destination lights up
        if (t > 0.55d) {
            double landing = (t - 0.55d) / 0.45d
            double landingFade = Math.pow(1d - landing, 1.8d)
            double spread = pulse.growPx * 0.6d * (1d - Math.pow(1d - landing, 3d))
            g2.setColor(new Color(red, green, blue, (int) Math.round(landingFade * 220d)))
            g2.setStroke(new BasicStroke((float) (1d + 2d * landingFade),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND))
            g2.draw(new RoundRectangle2D.Double(to.x - spread, to.y - spread,
                    to.width + 2d * spread, to.height + 2d * spread, 14d + spread, 14d + spread))
        }
    }

    // The tail itself: segments from the tail point up to the head, each thicker and more
    // opaque than the last, which is what reads as motion. The band is scaled to the node's
    // height - a fixed few pixels disappears next to a real node and reads as a scratch.
    private void paintTail(Graphics2D g2, double t, double eased, Rectangle from, Rectangle to,
            double fromX, double fromY, double toX, double toY, int red, int green, int blue,
            double strength) {
        double fade = Math.pow(1d - t, 1.4d) * strength
        double band = Math.max(7d, Math.min(from.height, to.height) * 0.5d)
        double head = eased
        double tail = Math.max(0d, eased - 0.45d)
        int segments = 16
        for (int i = 0; i < segments; i++) {
            double a = tail + (head - tail) * i / (double) segments
            double b = tail + (head - tail) * (i + 1) / (double) segments
            double weight = (i + 1) / (double) segments
            int alpha = (int) Math.round(fade * weight * weight * 200d)
            if (alpha <= 3) {
                continue
            }
            g2.setColor(new Color(red, green, blue, alpha))
            g2.setStroke(new BasicStroke((float) (2d + band * weight * fade),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND))
            g2.draw(new Line2D.Double(fromX + (toX - fromX) * a, fromY + (toY - fromY) * a,
                    fromX + (toX - fromX) * b, fromY + (toY - fromY) * b))
        }
    }

    // a translucent copy of the node slides over to the new place; with a tail behind it
    // when the style is 'comet'
    private void paintGhost(Graphics2D g2, VisualFxPulse pulse, double t, double eased,
            Rectangle from, Rectangle to, int red, int green, int blue) {
        double fade = Math.pow(1d - t, 1.3d)
        double x = from.x + (to.x - from.x) * eased
        double y = from.y + (to.y - from.y) * eased
        double width = from.width + (to.width - from.width) * eased
        double height = from.height + (to.height - from.height) * eased

        if ('comet' == pulse.style) {
            paintTail(g2, t, eased, from, to,
                    from.x + from.width / 2d, from.y + from.height / 2d,
                    to.x + to.width / 2d, to.y + to.height / 2d, red, green, blue, 0.75d)
        }

        g2.setColor(new Color(red, green, blue, (int) Math.round(fade * 60d)))
        g2.fill(new RoundRectangle2D.Double(x, y, width, height, 12d, 12d))
        g2.setColor(new Color(red, green, blue, (int) Math.round(fade * 215d)))
        g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND))
        g2.draw(new RoundRectangle2D.Double(x, y, width, height, 12d, 12d))

        // a faint line back to where it started, so the origin is readable at a glance
        // (pointless under a tail, which already covers that ground)
        double linkFade = 'comet' == pulse.style ? 0d : Math.pow(1d - t, 2.4d)
        if (linkFade > 0.02d) {
            g2.setColor(new Color(red, green, blue, (int) Math.round(linkFade * 120d)))
            g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    1f, [2f, 5f] as float[], 0f))
            g2.draw(new Line2D.Double(from.x + from.width / 2d, from.y + from.height / 2d,
                    x + width / 2d, y + height / 2d))
        }
    }

    // a curved line between the two places, with a bright head running along it
    private void paintArc(Graphics2D g2, VisualFxPulse pulse, double t, double eased,
            double fromX, double fromY, double toX, double toY, Rectangle to,
            int red, int green, int blue) {
        // Hold at full strength for the first stretch and only then fade. A plain
        // pow(1-t, 1.5) starts dimming from the very first frame, which is what made the
        // arc easy to miss - most of its life was already half transparent.
        double fade = t < ARC_HOLD ? 1d : Math.pow(1d - (t - ARC_HOLD) / (1d - ARC_HOLD), 1.4d)
        double dx = toX - fromX
        double dy = toY - fromY
        double distance = Math.max(1d, Math.sqrt(dx * dx + dy * dy))
        // bow the curve perpendicular to the trip, so short moves are still readable
        double bow = Math.min(90d, 26d + distance * 0.22d)
        double controlX = (fromX + toX) / 2d - dy / distance * bow
        double controlY = (fromY + toY) / 2d + dx / distance * bow
        // scaled to the node, like the comet tail: a fixed 3 px line vanishes next to a
        // full-size node, and the map zoom is not knowable from here
        double weight = Math.max(3.5d, Math.min(14d, to.height * 0.16d))

        QuadCurve2D curve = new QuadCurve2D.Double(fromX, fromY, controlX, controlY, toX, toY)
        // a wide, faint pass under a narrow, bright one: reads as a thick line with a glow
        // instead of a heavy slab
        g2.setColor(new Color(red, green, blue, (int) Math.round(fade * 70d)))
        g2.setStroke(new BasicStroke((float) (weight * 2.2d),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND))
        g2.draw(curve)
        g2.setColor(new Color(red, green, blue, (int) Math.round(fade * 235d)))
        g2.setStroke(new BasicStroke((float) weight, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND))
        g2.draw(curve)

        // quadratic Bezier at the eased parameter: the head of the trip
        double u = 1d - eased
        double headX = u * u * fromX + 2d * u * eased * controlX + eased * eased * toX
        double headY = u * u * fromY + 2d * u * eased * controlY + eased * eased * toY
        double radius = weight * 1.5d
        g2.setColor(new Color(red, green, blue, (int) Math.round(fade * 90d)))
        g2.fill(new Ellipse2D.Double(headX - radius * 1.8d, headY - radius * 1.8d,
                radius * 3.6d, radius * 3.6d))
        g2.setColor(new Color(red, green, blue, (int) Math.round(fade * 255d)))
        g2.fill(new Ellipse2D.Double(headX - radius, headY - radius, radius * 2d, radius * 2d))

        if (t > 0.5d) {
            double landing = (t - 0.5d) / 0.5d
            double landingFade = Math.pow(1d - landing, 1.5d)
            g2.setColor(new Color(red, green, blue, (int) Math.round(landingFade * 230d)))
            g2.setStroke(new BasicStroke((float) Math.max(2.5d, weight * 0.55d),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND))
            g2.draw(new RoundRectangle2D.Double(to.x - 3d, to.y - 3d,
                    to.width + 6d, to.height + 6d, 14d, 14d))
        }
    }

    // -----------------------------------------------------------------------
    // delete: everything is painted from geometry captured before the deletion,
    // because by now there is no node and no view to ask
    // -----------------------------------------------------------------------

    private void paintDelete(Graphics2D g2, VisualFxPulse pulse, long now) {
        // pulse.node is the ROOT - the deleted node is out of the map, and the root is the
        // fixed point the relayout leaves alone
        Rectangle anchor = nodeBounds(pulse.node)
        Rectangle gone = atNode(anchor, pulse.originOffset)
        if (gone == null) {
            return
        }
        Rectangle branch = atNode(anchor, pulse.areaOffset)
        double t = pulse.progress(now)
        double fade = Math.pow(1d - t, 1.4d)
        int red = pulse.color.getRed()
        int green = pulse.color.getGreen()
        int blue = pulse.color.getBlue()

        if ('shatter' == pulse.style) {
            paintShatter(g2, pulse, gone, t, fade, red, green, blue)
        }
        else if ('sweep' == pulse.style && branch != null) {
            // a delete is a closing sweep, exactly like a fold: same function, same hold
            paintSweep(g2, gone, branch, t, false, red, green, blue)
            paintSkeleton(g2, pulse, gone, t, red, green, blue)
        }
        else if ('ripple' == pulse.style || 'sparks' == pulse.style) {
            // the sweep, plus the create ripple's own vocabulary fired from the vacated spot:
            // the branch folds away while something marks where it used to be attached
            if (branch != null) {
                paintSweep(g2, gone, branch, t, false, red, green, blue)
                paintSkeleton(g2, pulse, gone, t, red, green, blue)
            }
            paintFlash(g2, gone, t, red, green, blue)
            if ('ripple' == pulse.style) {
                paintRings(g2, pulse, gone, t, red, green, blue, true)
            }
            paintSparks(g2, pulse, gone, t, red, green, blue, 1d)
        }
        else {
            paintImplode(g2, pulse, gone, t, fade, red, green, blue)
        }
    }

    // the node's outline shrinks to its centre and the rings close in after it
    private void paintImplode(Graphics2D g2, VisualFxPulse pulse, Rectangle gone, double t,
            double fade, int red, int green, int blue) {
        double shrink = Math.pow(1d - t, 1.8d)
        double centerX = gone.x + gone.width / 2d
        double centerY = gone.y + gone.height / 2d
        double width = gone.width * shrink
        double height = gone.height * shrink

        g2.setColor(new Color(red, green, blue, (int) Math.round(fade * 55d)))
        g2.fill(new RoundRectangle2D.Double(centerX - width / 2d, centerY - height / 2d,
                width, height, 12d, 12d))
        g2.setColor(new Color(red, green, blue, (int) Math.round(fade * 225d)))
        g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND))
        g2.draw(new RoundRectangle2D.Double(centerX - width / 2d, centerY - height / 2d,
                width, height, 12d, 12d))

        // a ring falling inwards, so the eye is pulled to the spot instead of away from it
        double reach = Math.max(pulse.growPx, Math.min(gone.width, gone.height) * 0.6d)
        double spread = reach * Math.pow(1d - t, 1.2d)
        int alpha = (int) Math.round(fade * 170d)
        if (alpha > 3) {
            g2.setColor(new Color(red, green, blue, alpha))
            g2.setStroke(new BasicStroke((float) (1d + 1.8d * fade),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND))
            g2.draw(new RoundRectangle2D.Double(gone.x - spread, gone.y - spread,
                    gone.width + 2d * spread, gone.height + 2d * spread,
                    14d + spread, 14d + spread))
        }
    }

    // the node breaks into pieces that fly apart and fall
    private void paintShatter(Graphics2D g2, VisualFxPulse pulse, Rectangle gone, double t,
            double fade, int red, int green, int blue) {
        int columns = 4
        int rows = 2
        double pieceWidth = gone.width / (double) columns
        double pieceHeight = gone.height / (double) rows
        double centerX = gone.x + gone.width / 2d
        double centerY = gone.y + gone.height / 2d
        Random random = new Random(pulse.seed)
        double eased = 1d - Math.pow(1d - t, 2d)
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                double x = gone.x + column * pieceWidth
                double y = gone.y + row * pieceHeight
                double pieceCenterX = x + pieceWidth / 2d
                double pieceCenterY = y + pieceHeight / 2d
                // each piece leaves along the direction it already sits in, plus some spin
                double driftX = (pieceCenterX - centerX) * 0.9d + (random.nextDouble() - 0.5d) * 20d
                double driftY = (pieceCenterY - centerY) * 0.5d + (random.nextDouble() - 0.5d) * 12d
                double gravity = 26d + random.nextDouble() * 22d
                double offsetX = driftX * eased
                double offsetY = driftY * eased + gravity * t * t
                int alpha = (int) Math.round(fade * 210d)
                if (alpha <= 3) {
                    continue
                }
                g2.setColor(new Color(red, green, blue, (int) Math.round(alpha * 0.22d)))
                g2.fill(new RoundRectangle2D.Double(x + offsetX, y + offsetY,
                        pieceWidth * 0.9d, pieceHeight * 0.9d, 4d, 4d))
                g2.setColor(new Color(red, green, blue, alpha))
                g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND))
                g2.draw(new RoundRectangle2D.Double(x + offsetX, y + offsetY,
                        pieceWidth * 0.9d, pieceHeight * 0.9d, 4d, 4d))
            }
        }
    }

    // -----------------------------------------------------------------------
    // fold / unfold: the same drawing, run inwards or outwards
    // -----------------------------------------------------------------------

    private void paintFolding(Graphics2D g2, VisualFxPulse pulse, long now) {
        Rectangle node = nodeBounds(pulse.node)
        if (node == null) {
            return
        }
        boolean opening = 'unfold' == pulse.kind
        double t = pulse.progress(now)
        // the effect runs outwards when the branch opens and inwards when it closes, so
        // the whole animation is the same curve read in one direction or the other
        double travel = opening ? 1d - Math.pow(1d - t, 3d) : Math.pow(1d - t, 2.2d)
        double fade = Math.pow(1d - t, 1.5d)
        int red = pulse.color.getRed()
        int green = pulse.color.getGreen()
        int blue = pulse.color.getBlue()

        // resolved against the node's CURRENT position, every frame
        Rectangle area = atNode(node, pulse.areaOffset)
        String style = pulse.style
        if (('sweep' == style || 'spokes' == style) && area == null) {
            style = 'ripple'    // no captured geometry: fall back to something that needs none
        }

        if ('sweep' == style) {
            paintSweep(g2, node, area, t, opening, red, green, blue)
            paintSkeleton(g2, pulse, node, t, red, green, blue)
        }
        else if ('chevrons' == style) {
            paintChevrons(g2, pulse, node, area, t, travel, fade, opening, red, green, blue)
        }
        else if ('spokes' == style) {
            paintSpokes(g2, pulse, node, t, travel, fade, opening, red, green, blue)
        }
        else {
            paintFoldRipple(g2, pulse, node, travel, fade, red, green, blue)
        }
    }

    // THE sweep. Every action that has one uses this: a rectangle travelling between a node
    // and the branch hanging off it. There are only two cases, and each of the four callers
    // is one of them:
    //
    //   opening (unfold, paste) - node -> branch, holding at the END
    //   closing (fold, delete)  - branch -> node, holding at the START
    //
    // The hold is not symmetry for its own sake: the informative instant differs. Opening, it
    // is the arrival on the branch just revealed; closing, it is the branch still standing
    // before it goes. Without it each direction misses its own moment - measured on the fold,
    // the branch was shown at full size and full alpha for 10 ms, a single frame.
    private void paintSweep(Graphics2D g2, Rectangle node, Rectangle branch, double t,
            boolean opening, int red, int green, int blue) {
        double travel
        double fade
        if (opening) {
            double grow = Math.min(1d, t / SWEEP_OPEN_TRAVEL)
            travel = 1d - Math.pow(1d - grow, 3d)
            fade = t < SWEEP_OPEN_TRAVEL ? 1d :
                    Math.pow(1d - (t - SWEEP_OPEN_TRAVEL) / (1d - SWEEP_OPEN_TRAVEL), 1.4d)
        }
        else if (t < SWEEP_CLOSE_HOLD) {
            travel = 1d
            fade = 1d
        }
        else {
            double shrink = (t - SWEEP_CLOSE_HOLD) / (1d - SWEEP_CLOSE_HOLD)
            travel = Math.pow(1d - shrink, 2.2d)
            fade = Math.pow(1d - shrink, 1.5d)
        }

        double x = node.x + (branch.x - node.x) * travel
        double y = node.y + (branch.y - node.y) * travel
        double width = node.width + (branch.width - node.width) * travel
        double height = node.height + (branch.height - node.height) * travel

        g2.setColor(new Color(red, green, blue, (int) Math.round(fade * SWEEP_FILL_ALPHA)))
        g2.fill(new RoundRectangle2D.Double(x, y, width, height, 16d, 16d))
        g2.setColor(new Color(red, green, blue, (int) Math.round(fade * SWEEP_EDGE_ALPHA)))
        g2.setStroke(new BasicStroke(SWEEP_EDGE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND))
        g2.draw(new RoundRectangle2D.Double(x, y, width, height, 16d, 16d))

        // The skeleton is drawn by the caller that owns the pulse - see paintSkeleton.
    }

    // The outlines of the nodes that stood inside the branch: held while the sweep is held,
    // and gone before the collapse ends. They do NOT travel with the sweep - they mark where
    // things WERE, and a rectangle that both shrinks and fades reads as mush. They fade faster
    // than the sweep so the collapse itself is not cluttered.
    private void paintSkeleton(Graphics2D g2, VisualFxPulse pulse, Rectangle node, double t,
            int red, int green, int blue) {
        List<Rectangle> skeleton = pulse.skeletonOffsets
        if (node == null || skeleton == null || skeleton.isEmpty()) {
            return
        }
        double ghost = t < SWEEP_CLOSE_HOLD ? 1d :
                Math.pow(1d - (t - SWEEP_CLOSE_HOLD) / (1d - SWEEP_CLOSE_HOLD), 2.6d)
        if (ghost * SKELETON_ALPHA <= 3d) {
            return
        }
        BufferedImage cached = skeletonImageOf(pulse, red, green, blue)
        if (cached != null) {
            // one blit instead of N strokes: at 250 outlines that is 3.1 ms rather than 13.2
            Composite previous = g2.getComposite()
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) ghost))
            g2.drawImage(cached, (int) (node.getX() + pulse.skeletonImageAt.getX()),
                    (int) (node.getY() + pulse.skeletonImageAt.getY()), null)
            g2.setComposite(previous)
            return
        }
        g2.setColor(new Color(red, green, blue, (int) Math.round(ghost * SKELETON_ALPHA)))
        g2.setStroke(new BasicStroke(SKELETON_EDGE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND))
        for (Rectangle ghostRect : skeleton) {
            g2.draw(new RoundRectangle2D.Double(node.x + ghostRect.getX(), node.y + ghostRect.getY(),
                    ghostRect.getWidth(), ghostRect.getHeight(), 8d, 8d))
        }
    }

    // Pre-renders the outlines once, but only when there are enough of them to be worth it -
    // below the threshold a blit costs more than simply stroking them. Returns null when the
    // caller should stroke directly. Built at full SKELETON_ALPHA; the per-frame fade is the
    // AlphaComposite on top.
    private BufferedImage skeletonImageOf(VisualFxPulse pulse, int red, int green, int blue) {
        if (pulse.skeletonImageTried) {
            return pulse.skeletonImage
        }
        pulse.skeletonImageTried = true
        List<Rectangle> skeleton = pulse.skeletonOffsets
        if (skeleton.size() < SKELETON_CACHE_MIN) {
            return null
        }
        Rectangle bounds = null
        for (Rectangle r : skeleton) {
            bounds = bounds == null ? new Rectangle(r) : bounds.union(r)
        }
        int pad = (int) Math.ceil(SKELETON_EDGE_WIDTH) + 1
        int width = (int) bounds.getWidth() + 2 * pad
        int height = (int) bounds.getHeight() + 2 * pad
        if (width <= 0 || height <= 0 || (long) width * height > 16_000_000L) {
            return null      // absurdly large branch: fall back to stroking, capped anyway
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        Graphics2D ig = image.createGraphics()
        try {
            ig.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            ig.setColor(new Color(red, green, blue, (int) Math.round(SKELETON_ALPHA)))
            ig.setStroke(new BasicStroke(SKELETON_EDGE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND))
            for (Rectangle r : skeleton) {
                ig.draw(new RoundRectangle2D.Double(r.getX() - bounds.getX() + pad,
                        r.getY() - bounds.getY() + pad, r.getWidth(), r.getHeight(), 8d, 8d))
            }
        }
        finally {
            ig.dispose()
        }
        pulse.skeletonImage = image
        pulse.skeletonImageAt = new Rectangle((int) bounds.getX() - pad, (int) bounds.getY() - pad,
                width, height)
        return image
    }

    // three chevrons on the children's side, moving away from the node or into it
    private void paintChevrons(Graphics2D g2, VisualFxPulse pulse, Rectangle node, Rectangle area,
            double t, double travel, double fade, boolean opening, int red, int green, int blue) {
        double[] direction = directionOf(node, area)
        double dirX = direction[0]
        double dirY = direction[1]
        // scaled to the node, not fixed in pixels: at a high zoom a 9 px chevron next to a
        // 100 px node is invisible, and the map zoom is not knowable from here
        double size = Math.max(7d, Math.min(26d, node.height * 0.22d))
        double reach = size * 3.4d
        g2.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND))
        double edgeX = node.x + node.width / 2d + dirX * (node.width / 2d + 6d)
        double edgeY = node.y + node.height / 2d + dirY * (node.height / 2d + 6d)
        for (int i = 0; i < 3; i++) {
            double slot = (i + 1) / 3d
            double distance = reach * slot * (opening ? travel : 1d) +
                    (opening ? 0d : reach * (1d - travel) * 0.4d)
            double alpha = fade * (opening ? slot : 1d - slot * 0.3d)
            int value = (int) Math.round(alpha * 230d)
            if (value <= 3) {
                continue
            }
            g2.setColor(new Color(red, green, blue, value))
            double cx = edgeX + dirX * distance
            double cy = edgeY + dirY * distance
            // the chevron points the way the branch is going
            double tipX = cx + dirX * size
            double tipY = cy + dirY * size
            double backX = cx - dirX * size * 0.2d
            double backY = cy - dirY * size * 0.2d
            g2.draw(new Line2D.Double(backX - dirY * size, backY + dirX * size, tipX, tipY))
            g2.draw(new Line2D.Double(backX + dirY * size, backY - dirX * size, tipX, tipY))
        }
    }

    // one line from the node to each direct child, lit one after the other
    private void paintSpokes(Graphics2D g2, VisualFxPulse pulse, Rectangle node, double t,
            double travel, double fade, boolean opening, int red, int green, int blue) {
        List<Rectangle> children = pulse.childrenOffset
        if (children == null || children.isEmpty()) {
            return
        }
        double fromX = node.x + node.width / 2d
        double fromY = node.y + node.height / 2d
        int count = children.size()
        for (int i = 0; i < count; i++) {
            Rectangle child = atNode(node, children.get(i))
            if (child == null) {
                continue
            }
            // stagger: top child first when opening, last when closing
            double order = count == 1 ? 0d : (opening ? i : count - 1 - i) / (double) count
            double local = (t - order * 0.35d) / (1d - order * 0.35d)
            if (local <= 0d) {
                continue
            }
            local = Math.min(1d, local)
            double reach = opening ? 1d - Math.pow(1d - local, 3d) : Math.pow(1d - local, 2.2d)
            double localFade = Math.pow(1d - local, 1.5d)
            int alpha = (int) Math.round(localFade * 210d)
            if (alpha <= 3) {
                continue
            }
            double toX = child.x + child.width / 2d
            double toY = child.y + child.height / 2d
            g2.setColor(new Color(red, green, blue, alpha))
            g2.setStroke(new BasicStroke((float) (2d + 3d * localFade),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND))
            g2.draw(new Line2D.Double(fromX, fromY, fromX + (toX - fromX) * reach,
                    fromY + (toY - fromY) * reach))
            double radius = 4d + 3d * localFade
            g2.fill(new Ellipse2D.Double(fromX + (toX - fromX) * reach - radius,
                    fromY + (toY - fromY) * reach - radius, radius * 2d, radius * 2d))
        }
    }

    // rings around the node, closing in or opening out
    private void paintFoldRipple(Graphics2D g2, VisualFxPulse pulse, Rectangle node,
            double travel, double fade, int red, int green, int blue) {
        // same reason as the chevrons: a ring 26 px away from a node twice that tall reads
        // as a thicker border, not as a ring
        double reach = Math.max(pulse.growPx, Math.min(node.width, node.height) * 0.55d)
        for (int ring = 0; ring < 2; ring++) {
            double offset = ring * 0.22d
            double spread = reach * Math.max(0d, travel - offset) / (1d - offset)
            int alpha = (int) Math.round(fade * (ring == 0 ? 225d : 140d))
            if (alpha <= 3 || spread <= 0d) {
                continue
            }
            g2.setColor(new Color(red, green, blue, alpha))
            g2.setStroke(new BasicStroke((float) (1d + 2.2d * fade),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND))
            g2.draw(new RoundRectangle2D.Double(node.x - spread, node.y - spread,
                    node.width + 2d * spread, node.height + 2d * spread,
                    14d + spread, 14d + spread))
        }
    }

    // Which way the branch grows, taken from the geometry rather than from
    // NodeView.isTopOrLeft() - that getter runs updateChildNodeViewLayout() on the way,
    // and a painting loop has no business mutating the view.
    private static double[] directionOf(Rectangle node, Rectangle area) {
        if (area == null) {
            return [1d, 0d] as double[]
        }
        double dx = (area.x + area.width / 2d) - (node.x + node.width / 2d)
        double dy = (area.y + area.height / 2d) - (node.y + node.height / 2d)
        double length = Math.sqrt(dx * dx + dy * dy)
        if (length < 1d) {
            return [1d, 0d] as double[]
        }
        return [dx / length, dy / length] as double[]
    }

    // a ring collapses into the place the node left, another opens where it arrived
    private void paintImplodeExplode(Graphics2D g2, VisualFxPulse pulse, double t,
            Rectangle from, Rectangle to, int red, int green, int blue) {
        // implosion, first half
        double implodeT = Math.min(1d, t / 0.5d)
        if (implodeT < 1d) {
            double spread = pulse.growPx * Math.pow(1d - implodeT, 1.6d)
            int alpha = (int) Math.round(Math.min(1d, 2d * (1d - implodeT)) * 200d)
            if (alpha > 3) {
                g2.setColor(new Color(red, green, blue, alpha))
                g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND))
                g2.draw(new RoundRectangle2D.Double(from.x - spread, from.y - spread,
                        from.width + 2d * spread, from.height + 2d * spread,
                        14d + spread, 14d + spread))
            }
        }
        // explosion, second half
        if (t > 0.35d) {
            double explodeT = (t - 0.35d) / 0.65d
            double spread = pulse.growPx * (1d - Math.pow(1d - explodeT, 3d))
            double fade = Math.pow(1d - explodeT, 1.8d)
            int alpha = (int) Math.round(fade * 225d)
            if (alpha > 3) {
                g2.setColor(new Color(red, green, blue, alpha))
                g2.setStroke(new BasicStroke((float) (1d + 2.4d * fade),
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND))
                g2.draw(new RoundRectangle2D.Double(to.x - spread, to.y - spread,
                        to.width + 2d * spread, to.height + 2d * spread,
                        14d + spread, 14d + spread))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// wiring: where an effect is fired from
// ---------------------------------------------------------------------------

class VisualFxDirector {
    private final int selectDurationMs
    private final String selectStyle
    private final Color selectColor
    private final int selectThrottleMs
    private long lastSelectAt = 0L
    private long selectMutedUntil = 0L
    private final int createDurationMs
    private final String createStyle
    private final boolean createSweepBranch
    private final int moveDurationMs
    private final String moveStyle
    private final String arcOrigin
    private final int frameMs
    private final int growPx
    private final int rings
    private final boolean sparks
    private final int sparkCount
    private final Color createColor
    private final Color moveColor
    private final int foldDurationMs
    private final int sweepOpenDurationMs
    final boolean sweepSkeleton
    private final String foldStyle
    private final Color foldColor
    private final int deleteDurationMs
    private final String deleteStyle
    private final Color deleteColor
    private long counter = 0L

    // named settings, not a dozen positional arguments: the list only grows as actions
    // are added, and a swapped pair of ints would fail silently
    VisualFxDirector(Map<String, Object> settings) {
        this.selectDurationMs = (int) settings.get('selectDurationMs')
        this.selectStyle = (String) settings.get('selectStyle')
        this.selectColor = (Color) settings.get('selectColor')
        this.selectThrottleMs = (int) settings.get('selectThrottleMs')
        this.createDurationMs = (int) settings.get('createDurationMs')
        this.createStyle = (String) settings.get('createStyle')
        this.createSweepBranch = (boolean) settings.get('createSweepBranch')
        this.moveDurationMs = (int) settings.get('moveDurationMs')
        this.moveStyle = (String) settings.get('moveStyle')
        this.arcOrigin = (String) settings.get('arcOrigin')
        this.foldDurationMs = (int) settings.get('foldDurationMs')
        this.sweepOpenDurationMs = (int) settings.get('sweepOpenDurationMs')
        this.sweepSkeleton = (boolean) settings.get('sweepSkeleton')
        this.foldStyle = (String) settings.get('foldStyle')
        this.deleteDurationMs = (int) settings.get('deleteDurationMs')
        this.deleteStyle = (String) settings.get('deleteStyle')
        this.deleteColor = (Color) settings.get('deleteColor')
        this.frameMs = (int) settings.get('frameMs')
        this.growPx = (int) settings.get('growPx')
        this.rings = (int) settings.get('rings')
        this.sparks = (boolean) settings.get('sparks')
        this.sparkCount = (int) settings.get('sparkCount')
        this.createColor = (Color) settings.get('createColor')
        this.moveColor = (Color) settings.get('moveColor')
        this.foldColor = (Color) settings.get('foldColor')
    }

    // The map view the node belongs to, but only when it is the one on screen
    MapView currentViewOf(NodeModel node) {
        if (node == null) {
            return null
        }
        Controller controller = Controller.getCurrentController()
        if (controller == null) {
            return null
        }
        MapView mapView = (MapView) controller.getMapViewManager().getMapView()
        if (mapView == null || mapView.getParent() == null) {
            return null
        }
        MapModel map = mapView.getMap()
        return map != null && map.is(node.getMap()) ? mapView : null
    }

    // Rectangles are captured as an offset from an ANCHOR node that survives the edit, never
    // in absolute map coordinates: the edit relayouts the map and moves everything around.
    private static Rectangle offsetFrom(Rectangle anchor, Rectangle target) {
        if (anchor == null || target == null) {
            return null
        }
        return new Rectangle((int) (target.getX() - anchor.getX()), (int) (target.getY() - anchor.getY()),
                (int) target.getWidth(), (int) target.getHeight())
    }

    // [node rect, subtree rect], both relative to the anchor's rect
    Rectangle[] capture(NodeModel node, NodeModel anchor) {
        MapView mapView = currentViewOf(node)
        if (mapView == null) {
            return null
        }
        Rectangle anchorRect = VisualFxGeometry.contentRectInMap(mapView, anchor)
        if (anchorRect == null) {
            return null
        }
        return [offsetFrom(anchorRect, VisualFxGeometry.contentRectInMap(mapView, node)),
                offsetFrom(anchorRect, VisualFxGeometry.subtreeRectInMap(mapView, node))] as Rectangle[]
    }

    // One offset per node inside the branch about to be deleted, for the skeleton. Walked
    // here, once, in the pre-event - the only moment those nodes still have views.
    // Relative to the DELETED NODE, not to the parent that anchors the pulse: paintSkeleton is
    // handed the deleted node's resolved rectangle, so the two have to agree. Measuring from
    // the parent instead put the whole skeleton off by the node-to-parent distance - which on
    // a wide map is most of the screen.
    List<Rectangle> captureSkeleton(NodeModel node) {
        MapView mapView = currentViewOf(node)
        if (mapView == null) {
            return null
        }
        Rectangle nodeRect = VisualFxGeometry.contentRectInMap(mapView, node)
        if (nodeRect == null) {
            return null
        }
        List<Rectangle> out = new ArrayList<Rectangle>()
        collectSkeleton(mapView, node, nodeRect, out)
        return out.isEmpty() ? null : out
    }

    private static void collectSkeleton(MapView mapView, NodeModel node, Rectangle anchorRect,
            List<Rectangle> out) {
        if (out.size() >= VisualFxOverlay.SKELETON_MAX) {
            return
        }
        Rectangle own = VisualFxGeometry.contentRectInMap(mapView, node)
        if (own != null) {
            out.add(offsetFrom(anchorRect, own))
        }
        if (node.isFolded()) {
            return      // its descendants have no views, and were not on screen anyway
        }
        for (NodeModel child : node.getChildren()) {
            collectSkeleton(mapView, child, anchorRect, out)
        }
    }

    // Muted for a moment after any structural edit: moving or deleting reselects a neighbour
    // before the edit event arrives, and without this every move would flash a selection on
    // the wrong node first. Same fix the sound effects needed.
    void muteSelect(long millis) {
        selectMutedUntil = System.currentTimeMillis() + millis
    }

    void fireSelect(NodeModel node) {
        long now = System.currentTimeMillis()
        if (now < selectMutedUntil || now - lastSelectAt < selectThrottleMs) {
            return
        }
        lastSelectAt = now
        VisualFxPulse pulse = fire(node, 'select', selectDurationMs, selectColor)
        if (pulse != null) {
            pulse.style = selectStyle
        }
    }

    void fireCreate(NodeModel node) {
        VisualFxPulse pulse = fire(node, 'create', createDurationMs, createColor)
        if (pulse == null) {
            return
        }
        pulse.style = createStyle
        // a plain new node has no children, so this costs nothing there; it only draws for an
        // insertion that arrived with a subtree, which in practice means a paste
        pulse.sweepBranch = createSweepBranch && node.hasChildren()
        if (pulse.sweepBranch) {
            // stretch the PULSE to the shared opening-sweep length, but leave the style on its
            // own clock: the ripple keeps the timing it was tuned to
            pulse.durationMs = Math.max(createDurationMs, sweepOpenDurationMs)
        }
    }

    Rectangle captureOrigin(NodeModel node) {
        MapView mapView = currentViewOf(node)
        return mapView == null ? null : VisualFxGeometry.contentRectInMap(mapView, node)
    }

    // The slot stays in absolute map coordinates - see the note on VisualFxPulse. The arc may
    // additionally be pinned to the previous parent, which is resolved live; the decision is
    // taken here so the painter only has to ask "is there an anchor?".
    void fireMove(NodeModel node, Rectangle originInMap, NodeModel oldParent, boolean parentChanged) {
        if (originInMap == null) {
            return
        }
        VisualFxPulse pulse = fire(node, 'move', moveDurationMs, moveColor)
        if (pulse == null) {
            return
        }
        pulse.originInMap = originInMap
        boolean usesParent = 'arc' == moveStyle && oldParent != null &&
                ('parent' == arcOrigin || ('auto' == arcOrigin && parentChanged))
        if (usesParent) {
            pulse.originAnchor = oldParent
        }
    }

    // Everything is anchored on the ROOT: the deleted node is out of the map by now, so it can
    // be neither measured nor used to resolve which view the effect belongs to, and the root
    // is the one node the relayout never moves on screen (see the note in onPreNodeDelete).
    void fireDelete(NodeModel anchor, Rectangle nodeOffset, Rectangle branchOffset,
            List<Rectangle> skeletonOffsets) {
        if (nodeOffset == null) {
            return
        }
        VisualFxPulse pulse = fire(anchor, 'delete', deleteDurationMs, deleteColor)
        if (pulse != null) {
            pulse.style = deleteStyle
            pulse.originOffset = nodeOffset
            pulse.areaOffset = branchOffset
            pulse.skeletonOffsets = sweepSkeleton ? skeletonOffsets : null
        }
    }

    // areaOffset / childrenOffset are relative to the node's own rectangle at capture time,
    // NOT absolute map coordinates: the fold relayouts the map underneath them
    void fireFolding(NodeModel node, String kind, Rectangle areaOffset,
            List<Rectangle> childrenOffset, List<Rectangle> skeletonOffsets) {
        VisualFxPulse pulse = fire(node, kind,
                'unfold' == kind ? sweepOpenDurationMs : foldDurationMs, foldColor)
        if (pulse != null) {
            pulse.style = foldStyle
            pulse.areaOffset = areaOffset
            pulse.childrenOffset = childrenOffset
            // only a fold shows it: on an unfold those nodes are on screen already
            pulse.skeletonOffsets = (sweepSkeleton && 'fold' == kind) ? skeletonOffsets : null
        }
    }

    private VisualFxPulse fire(NodeModel node, String kind, int durationMs, Color forced) {
        MapView mapView = currentViewOf(node)
        if (mapView == null) {
            return null
        }
        MapViewScrollPane scrollPane =
                (MapViewScrollPane) SwingUtilities.getAncestorOfClass(MapViewScrollPane, mapView)
        if (scrollPane == null) {
            return null
        }
        VisualFxOverlay overlay = overlayOf(scrollPane, mapView)
        if (overlay == null) {
            return null
        }
        VisualFxPulse pulse = new VisualFxPulse()
        pulse.kind = kind
        pulse.style = moveStyle
        pulse.node = node
        pulse.startedAt = System.currentTimeMillis()
        // the two clocks coincide unless a caller stretches the pulse past its style
        pulse.styleDurationMs = durationMs
        pulse.durationMs = durationMs
        pulse.growPx = growPx
        pulse.rings = rings
        pulse.sparks = sparks
        pulse.sparkCount = sparkCount
        pulse.color = forced == null ? defaultColor(mapView) : forced
        pulse.seed = counter++
        overlay.add(pulse)
        return pulse
    }

    private VisualFxOverlay overlayOf(MapViewScrollPane scrollPane, MapView mapView) {
        for (Component component : scrollPane.getComponents()) {
            if (VisualFxOverlay.OVERLAY_NAME == component.getName()) {
                // a leftover from an older compilation of this script cannot be cast, but it
                // can still be removed: the fresh overlay below replaces it
                if (component instanceof VisualFxOverlay) {
                    ((VisualFxOverlay) component).syncBounds()
                    return (VisualFxOverlay) component
                }
                scrollPane.remove(component)
            }
        }
        VisualFxOverlay overlay = new VisualFxOverlay(mapView, frameMs)
        overlay.setBounds(0, 0, scrollPane.getWidth(), scrollPane.getHeight())
        scrollPane.add(overlay)
        // add() puts the component BEHIND. Sitting just in front of the viewport keeps the
        // effect over the map and still below any other overlay (a search panel, say).
        int viewportIndex = 0
        Component[] components = scrollPane.getComponents()
        for (int i = 0; i < components.length; i++) {
            if (components[i].is(scrollPane.getViewport())) {
                viewportIndex = i
                break
            }
        }
        scrollPane.setComponentZOrder(overlay, viewportIndex)
        return overlay
    }

    private static Color defaultColor(MapView mapView) {
        Color background = mapView.getBackground()
        if (background == null) {
            return new Color(255, 170, 40)
        }
        double luminance = 0.2126d * background.getRed() + 0.7152d * background.getGreen() +
                0.0722d * background.getBlue()
        return luminance < 128d ? new Color(120, 220, 255) : new Color(255, 150, 20)
    }
}

class VisualFxFoldWatcher implements AWTEventListener {
    private final VisualFxDirector director
    private final boolean effectOnFold
    private final boolean effectOnUnfold
    private final Timer timer
    private MapModel lastMap = null
    private Set<NodeModel> foldedBefore = null
    private Map<NodeModel, Object[]> geometryBefore = new HashMap<NodeModel, Object[]>()
    // the previous scan's flat rectangle list; a folded node's skeleton is a slice of it
    private List<Rectangle> flatBefore = new ArrayList<Rectangle>()
    private long muteUntil = 0L

    VisualFxFoldWatcher(VisualFxDirector director, boolean effectOnFold, boolean effectOnUnfold, int delayMs) {
        this.director = director
        this.effectOnFold = effectOnFold
        this.effectOnUnfold = effectOnUnfold
        this.timer = new Timer(delayMs, { ActionEvent event -> check() } as ActionListener)
        this.timer.setRepeats(false)
    }

    @Override
    void eventDispatched(AWTEvent event) {
        int id = event.getID()
        // MOUSE_ENTERED/EXITED rain down while the pointer crosses node views and would
        // restart the timer forever
        boolean relevant = id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_RELEASED ||
                id == MouseEvent.MOUSE_CLICKED || id == KeyEvent.KEY_PRESSED ||
                id == KeyEvent.KEY_RELEASED
        if (relevant) {
            timer.restart()
        }
    }

    // a structural edit reshapes the tree as well; let its own effect play alone
    void mute(long millis) {
        muteUntil = System.currentTimeMillis() + millis
        timer.restart()
    }

    void stop() {
        timer.stop()
    }

    void resync() {
        check()
    }

    void check() {
        Controller controller = Controller.getCurrentController()
        MapView mapView = controller == null ? null : (MapView) controller.getMapViewManager().getMapView()
        NodeView root = mapView == null ? null : mapView.getRoot()
        if (root == null || mapView.getParent() == null) {
            lastMap = null
            foldedBefore = null
            return
        }
        Rectangle viewportInMap = SwingUtilities.convertRectangle(mapView.getParent(),
                mapView.getParent().getBounds(), mapView)
        Set<NodeModel> foldedNow = new HashSet<NodeModel>()
        Map<NodeModel, Object[]> geometryNow = new HashMap<NodeModel, Object[]>()
        List<Rectangle> flatNow = new ArrayList<Rectangle>()
        VisualFxFoldScanner.scan(root, 0, 0, viewportInMap, foldedNow, geometryNow, flatNow)

        MapModel map = mapView.getMap()
        boolean comparable = lastMap != null && lastMap.is(map) && foldedBefore != null &&
                System.currentTimeMillis() >= muteUntil
        Set<NodeModel> previouslyFolded = foldedBefore
        Map<NodeModel, Object[]> previousGeometry = geometryBefore
        List<Rectangle> previousFlat = flatBefore
        lastMap = map
        foldedBefore = foldedNow
        geometryBefore = geometryNow
        flatBefore = flatNow
        if (!comparable) {
            return
        }

        // A node left the folded set either because it was unfolded or because it stopped
        // being visible (an ancestor closed over it, or it was deleted). Only the first is
        // an unfold, and the model still knows which: an unfolded node reads folded=false.
        List<NodeModel> unfolded = new ArrayList<NodeModel>()
        for (NodeModel node : previouslyFolded) {
            if (!foldedNow.contains(node) && !node.isFolded() && mapView.getNodeView(node) != null) {
                unfolded.add(node)
            }
        }
        // The mirror image: a node enters the folded set when it folds, but ALSO when an
        // ancestor opens and reveals it already folded. Descendants of what just unfolded
        // are therefore not news.
        List<NodeModel> folded = new ArrayList<NodeModel>()
        for (NodeModel node : foldedNow) {
            if (previouslyFolded.contains(node) || isDescendantOfAny(node, unfolded)) {
                continue
            }
            folded.add(node)
        }

        if (effectOnUnfold) {
            for (NodeModel node : unfolded) {
                fireFrom(geometryNow.get(node), flatNow, node, 'unfold')
            }
        }
        if (effectOnFold) {
            for (NodeModel node : folded) {
                // the children are gone now: the area they covered is only in the previous snapshot
                fireFrom(previousGeometry.get(node), previousFlat, node, 'fold')
            }
        }
    }

    // Turn a captured snapshot into offsets from the node's own rectangle. Absolute map
    // coordinates cannot be used: a fold relayouts the map and moves every node, so a
    // rectangle captured before it lands somewhere else (measured: 339 px, enough to put a
    // branch that was above the node below it - which is exactly what looked like a mirror).
    private void fireFrom(Object[] geometry, List<Rectangle> flat, NodeModel node, String kind) {
        if (geometry == null || geometry.length < 5) {
            director.fireFolding(node, kind, null, null, null)
            return
        }
        Rectangle own = (Rectangle) geometry[2]
        Rectangle subtree = (Rectangle) geometry[0]
        List<Rectangle> children = (List<Rectangle>) geometry[1]
        if (own == null || subtree == null) {
            director.fireFolding(node, kind, null, null, null)
            return
        }
        // casts are not optional: reading .x/.width in Groovy hits the Rectangle2D getters,
        // which return double, and Rectangle has no (double, double, double, double) ctor
        Rectangle areaOffset = new Rectangle((int) (subtree.getX() - own.getX()),
                (int) (subtree.getY() - own.getY()),
                (int) subtree.getWidth(), (int) subtree.getHeight())
        List<Rectangle> childrenOffset = new ArrayList<Rectangle>()
        if (children != null) {
            for (Rectangle child : children) {
                childrenOffset.add(new Rectangle((int) (child.getX() - own.getX()),
                        (int) (child.getY() - own.getY()),
                        (int) child.getWidth(), (int) child.getHeight()))
            }
        }
        // The skeleton: every DESCENDANT rectangle, which is the slice of the flat list right
        // after the node itself. Converted here, once per fold, rather than stored per node.
        List<Rectangle> skeleton = null
        int from = (int) geometry[3]
        int to = (int) geometry[4]
        if (flat != null && to <= flat.size() && to - from > 1) {
            int limit = Math.min(to, from + 1 + VisualFxOverlay.SKELETON_MAX)
            skeleton = new ArrayList<Rectangle>()
            for (int i = from + 1; i < limit; i++) {   // +1 skips the node's own rectangle
                Rectangle r = flat.get(i)
                skeleton.add(new Rectangle((int) (r.getX() - own.getX()),
                        (int) (r.getY() - own.getY()),
                        (int) r.getWidth(), (int) r.getHeight()))
            }
        }
        director.fireFolding(node, kind, areaOffset, childrenOffset, skeleton)
    }

    private static boolean isDescendantOfAny(NodeModel node, List<NodeModel> ancestors) {
        if (ancestors.isEmpty()) {
            return false
        }
        NodeModel current = node.getParentNode()
        while (current != null) {
            for (NodeModel ancestor : ancestors) {
                if (current.is(ancestor)) {
                    return true
                }
            }
            current = current.getParentNode()
        }
        return false
    }
}

// Global on the mode controller, so it survives reloads and covers every map without
// re-registering. onSelect is synchronous with the selection itself.
class VisualFxSelectionListener implements INodeSelectionListener {
    private final VisualFxDirector director

    VisualFxSelectionListener(VisualFxDirector director) {
        this.director = director
    }

    @Override
    void onSelect(NodeModel node) {
        if (node == null) {
            return
        }
        // it also fires when a map is opened or a tab is switched (the new root gets selected),
        // and once per node of a multi-selection; the throttle inside absorbs the latter
        director.fireSelect(node)
    }
}

class VisualFxMapListener implements IMapChangeListener {
    private final VisualFxDirector director
    private final boolean effectOnCreate
    private final boolean effectOnMove
    private final boolean effectOnDelete
    private final boolean deleteAnchorIsRoot
    private final VisualFxFoldWatcher watcher      // null when no folding effect is enabled
    private final long foldMuteMillis
    private final long selectMuteMillis
    // where each node about to be moved was standing, in map coordinates
    private final Map<NodeModel, Object[]> origins = new LinkedHashMap<NodeModel, Object[]>()
    // and what each node about to be deleted covered
    private final Map<NodeModel, Object[]> deletions = new LinkedHashMap<NodeModel, Object[]>()

    VisualFxMapListener(VisualFxDirector director, boolean effectOnCreate, boolean effectOnMove,
            boolean effectOnDelete, String deleteAnchor, VisualFxFoldWatcher watcher,
            long foldMuteMillis, long selectMuteMillis) {
        this.director = director
        this.effectOnCreate = effectOnCreate
        this.effectOnMove = effectOnMove
        this.effectOnDelete = effectOnDelete
        this.deleteAnchorIsRoot = 'root'.equalsIgnoreCase(deleteAnchor)
        this.watcher = watcher
        this.foldMuteMillis = foldMuteMillis
        this.selectMuteMillis = selectMuteMillis
    }

    // Creating, moving or deleting a node reshapes the tree, and the fold watcher would
    // read that reshaping as folding. Mute it around any structural edit - this runs for
    // every edit, including the ones whose own effect is switched off. The selection effect
    // is muted too, because an edit reselects a neighbour on the way.
    private void muteWatcher() {
        if (watcher != null) {
            watcher.mute(foldMuteMillis)
        }
        director.muteSelect(selectMuteMillis)
    }

    // Same shape as the move: the only moment the node can still be measured is before it
    // goes. Unlike the move, nothing can be recovered afterwards - the whole delete effect is
    // drawn from what is captured here, as offsets from the PARENT, which survives.
    @Override
    void onPreNodeDelete(NodeDeletionEvent event) {
        if (!effectOnDelete) {
            return
        }
        purge()
        // The anchor is captured here and carried to onNodeDeleted: by then the node is
        // detached and can no longer name its own parent.
        NodeModel parent = event.node.getParentNode()
        NodeModel anchor = (deleteAnchorIsRoot || parent == null) ?
                event.node.getMap().getRootNode() : parent
        Rectangle[] captured = director.capture(event.node, anchor)
        if (captured != null && captured[0] != null) {
            deletions.put(event.node, [captured, Long.valueOf(System.currentTimeMillis()),
                                       director.sweepSkeleton ?
                                               director.captureSkeleton(event.node) : null,
                                       anchor] as Object[])
        }
    }

    @Override
    void onNodeDeleted(NodeDeletionEvent event) {
        muteWatcher()
        if (!effectOnDelete) {
            return
        }
        Object[] entry = deletions.remove(event.node)
        if (entry == null) {
            return
        }
        Rectangle[] captured = (Rectangle[]) entry[0]
        director.fireDelete((NodeModel) entry[3], captured[0], captured[1],
                (List<Rectangle>) entry[2])
    }

    @Override
    void onNodeInserted(NodeModel parent, NodeModel child, int newIndex) {
        muteWatcher()
        if (!effectOnCreate) {
            return
        }
        // The NodeView of a brand new node may not exist yet while the insertion is still
        // being broadcast. That needs no invokeLater here: the effect resolves its geometry
        // on every frame, so a frame that finds no view simply paints nothing and the next
        // one - 16 ms later, with the layout done - draws in the right place.
        director.fireCreate(child)
    }

    // The old position is gone by the time onNodeMoved arrives, so measure it here, while the
    // node still stands where it stood.
    @Override
    void onPreNodeMoved(NodeMoveEvent event) {
        if (!effectOnMove) {
            return
        }
        purge()
        Rectangle origin = director.captureOrigin(event.child)
        if (origin != null) {
            origins.put(event.child, [origin, Long.valueOf(System.currentTimeMillis())] as Object[])
        }
    }

    @Override
    void onNodeMoved(NodeMoveEvent event) {
        muteWatcher()
        if (!effectOnMove) {
            return
        }
        Object[] captured = origins.remove(event.child)
        if (captured == null) {
            return
        }
        boolean parentChanged = event.oldParent != null && event.newParent != null &&
                !event.oldParent.is(event.newParent)
        director.fireMove(event.child, (Rectangle) captured[0], event.oldParent, parentChanged)
    }

    // a pre-event whose edit never happened would sit here forever
    private void purge() {
        long deadline = System.currentTimeMillis() - 3000L
        for (Map<NodeModel, Object[]> pending : [origins, deletions]) {
            Iterator<Map.Entry<NodeModel, Object[]>> iterator = pending.entrySet().iterator()
            while (iterator.hasNext()) {
                Map.Entry<NodeModel, Object[]> entry = iterator.next()
                if (((Long) entry.getValue()[1]).longValue() < deadline) {
                    iterator.remove()
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// install / uninstall (running the script again toggles the effects off)
// ---------------------------------------------------------------------------

List<Component> findOverlays(Container container, List<Component> found) {
    for (Component component : container.getComponents()) {
        if (VisualFxOverlay.OVERLAY_NAME == component.getName()) {
            found.add(component)
        }
        else if (component instanceof Container) {
            findOverlays((Container) component, found)
        }
    }
    return found
}

void removeOverlays() {
    List<Component> installed = new ArrayList<Component>()
    for (Window window : Window.getWindows()) {
        findOverlays(window, installed)
    }
    for (Component component : installed) {
        try {
            component.stop()
        }
        catch (Throwable ignored) {
        }
        Container parent = component.getParent()
        if (parent != null) {
            parent.remove(component)
            parent.repaint()
        }
    }
}

Controller controller = Controller.getCurrentController()
def mapController = controller.getModeController().getMapController()
Toolkit toolkit = Toolkit.getDefaultToolkit()

boolean wasInstalled = false

Object teardown = UIManager.get(INSTALL_KEY)
if (teardown != null) {
    wasInstalled = true
    UIManager.put(INSTALL_KEY, null)
    try {
        ((Runnable) teardown).run()
    }
    catch (Throwable ignored) {
    }
}

// leftovers from an older compilation of this script (class identity differs, name does not)
mapController.getMapChangeListeners()
        .findAll { it.getClass().getSimpleName() == VisualFxMapListener.getSimpleName() }
        .each {
            wasInstalled = true
            mapController.removeMapChangeListener(it)
        }
// copy the list before removing while iterating: the getter hands back the live one
new ArrayList(mapController.getNodeSelectionListeners())
        .findAll { it.getClass().getSimpleName() == VisualFxSelectionListener.getSimpleName() }
        .each {
            wasInstalled = true
            mapController.removeNodeSelectionListener(it)
        }

for (Object registered : toolkit.getAWTEventListeners()) {
    Object actual = registered instanceof AWTEventListenerProxy ?
            ((AWTEventListenerProxy) registered).getListener() : registered
    if (actual.getClass().getSimpleName() == VisualFxFoldWatcher.getSimpleName()) {
        wasInstalled = true
        toolkit.removeAWTEventListener((AWTEventListener) actual)
        try {
            actual.stop()
        }
        catch (Throwable ignored) {
        }
    }
}

// overlays live in the scroll pane of each tab, so they outlive the listener: sweep the
// whole component tree instead of trusting a list kept by the previous run
List<Component> orphans = new ArrayList<Component>()
for (Window window : Window.getWindows()) {
    findOverlays(window, orphans)
}
if (!orphans.isEmpty()) {
    wasInstalled = true
}
removeOverlays()

String message
if (wasInstalled) {
    message = 'Visual effects: OFF'
}
else if (!EFFECT_ON_SELECT && !EFFECT_ON_CREATE && !EFFECT_ON_MOVE && !EFFECT_ON_DELETE &&
        !EFFECT_ON_FOLD && !EFFECT_ON_UNFOLD) {
    message = 'Visual effects: nothing enabled'
}
else {
    VisualFxDirector director = new VisualFxDirector([
            selectDurationMs: SELECT_DURATION_MS,
            selectStyle     : SELECT_STYLE,
            selectColor     : SELECT_COLOR,
            selectThrottleMs: SELECT_THROTTLE_MS,
            createDurationMs: CREATE_DURATION_MS,
            createStyle     : CREATE_STYLE,
            createSweepBranch: CREATE_SWEEP_BRANCH,
            moveDurationMs  : MOVE_DURATION_MS,
            moveStyle       : MOVE_STYLE,
            arcOrigin       : ARC_ORIGIN,
            deleteDurationMs: DELETE_DURATION_MS,
            deleteStyle     : DELETE_STYLE,
            foldDurationMs  : FOLD_DURATION_MS,
            sweepOpenDurationMs: SWEEP_OPEN_DURATION_MS,
            sweepSkeleton   : SWEEP_SKELETON,
            foldStyle       : FOLD_STYLE,
            frameMs         : FRAME_MS,
            growPx          : GROW_PX,
            rings           : RINGS,
            sparks          : SPARKS,
            sparkCount      : SPARK_COUNT,
            createColor     : CREATE_COLOR,
            moveColor       : MOVE_COLOR,
            deleteColor     : DELETE_COLOR,
            foldColor       : FOLD_COLOR,
    ] as Map<String, Object>)

    boolean watchesFolding = EFFECT_ON_FOLD || EFFECT_ON_UNFOLD
    VisualFxFoldWatcher watcher = watchesFolding ?
            new VisualFxFoldWatcher(director, EFFECT_ON_FOLD, EFFECT_ON_UNFOLD, FOLD_POLL_DELAY_MS) : null
    // the map listener is needed even with every structural effect off, because it is what
    // mutes the fold watcher around an edit
    VisualFxMapListener listener = new VisualFxMapListener(director, EFFECT_ON_CREATE,
            EFFECT_ON_MOVE, EFFECT_ON_DELETE, DELETE_ANCHOR, watcher, FOLD_MUTE_AFTER_EDIT_MS,
            SELECT_MUTE_AFTER_EDIT_MS)
    mapController.addMapChangeListener(listener)
    VisualFxSelectionListener selectionListener = EFFECT_ON_SELECT ?
            new VisualFxSelectionListener(director) : null
    if (selectionListener != null) {
        mapController.addNodeSelectionListener(selectionListener)
    }
    if (watcher != null) {
        toolkit.addAWTEventListener(watcher, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.KEY_EVENT_MASK)
        watcher.resync()
    }

    UIManager.put(INSTALL_KEY, { ->
        mapController.removeMapChangeListener(listener)
        if (selectionListener != null) {
            mapController.removeNodeSelectionListener(selectionListener)
        }
        if (watcher != null) {
            toolkit.removeAWTEventListener(watcher)
            watcher.stop()
        }
        removeOverlays()
    } as Runnable)

    List<String> enabled = new ArrayList<String>()
    if (EFFECT_ON_SELECT) {
        enabled.add('select:' + SELECT_STYLE)
    }
    if (EFFECT_ON_CREATE) {
        enabled.add('create:' + CREATE_STYLE)
    }
    if (EFFECT_ON_MOVE) {
        enabled.add('move:' + MOVE_STYLE + ('arc' == MOVE_STYLE ? '(' + ARC_ORIGIN + ')' : ''))
    }
    if (EFFECT_ON_DELETE) {
        enabled.add('delete:' + DELETE_STYLE)
    }
    if (watchesFolding) {
        enabled.add((EFFECT_ON_FOLD && EFFECT_ON_UNFOLD ? 'fold/unfold' :
                (EFFECT_ON_FOLD ? 'fold' : 'unfold')) + ':' + FOLD_STYLE)
    }
    message = 'Visual effects: ON [' + enabled.join(' ') + ']'
}

controller.getViewController().out(message)
return message
