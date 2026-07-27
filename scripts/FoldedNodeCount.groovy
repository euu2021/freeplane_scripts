// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// Discussion thread: https://github.com/freeplane/freeplane/discussions/2956
// Version: 1.0

/*
 * Folded node count -- shows how many nodes are hidden inside a folded branch,
 * drawn in the folding mark itself. Implements the request in
 * https://github.com/freeplane/freeplane/issues/1320
 *
 * The colour follows the order of magnitude, so a map can be scanned without
 * reading every number: white up to 10, yellow up to 99, orange up to 999, red
 * above that. A number too long for the available space is abbreviated (4.6k).
 *
 * Run it once to switch it on, run it again to switch it off. The map is never
 * modified: the count is computed while painting and nothing is stored in it.
 *
 * How it works, and the caveat that comes with it: the script replaces the
 * Drawable used by FoldingMark.FOLDING_CIRCLE_FOLDED / _UNFOLDED, so the number
 * is painted inside the node's own painting pass. That is what makes spotlight
 * dimming, printing, image export, zoom and the map overview apply to it for
 * free, and it also makes the script work in every open map at once and survive
 * reloading a map. Reaching that Drawable needs reflection over internal
 * classes, which is not a supported API -- so a future Freeplane release may
 * well break this script.
 *
 * Subtree sizes are cached and invalidated along the ancestor chain when nodes
 * are inserted, deleted or moved. On a map with 22,177 nodes the painting cost
 * measured about 1.6 ms per repaint.
 */

import groovy.transform.CompileStatic

import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import javax.swing.UIManager
import org.freeplane.features.map.IMapChangeListener
import org.freeplane.features.map.NodeDeletionEvent
import org.freeplane.features.map.NodeModel
import org.freeplane.features.map.NodeMoveEvent
import org.freeplane.features.mode.Controller
import org.freeplane.view.swing.map.FoldingMark
import org.freeplane.view.swing.map.NodeView

@CompileStatic
class SubtreeSizes implements IMapChangeListener {
    private final Map<NodeModel, Integer> cache = new HashMap<NodeModel, Integer>()
    long hits = 0, misses = 0

    int of(NodeModel node) {
        Integer cached = cache.get(node)
        if (cached != null) { hits++; return cached.intValue() }
        misses++
        int total = 0
        for (NodeModel child : node.getChildren()) total += 1 + of(child)
        cache.put(node, Integer.valueOf(total))
        return total
    }

    private void invalidateAncestors(NodeModel node) {
        NodeModel current = node
        while (current != null) {
            cache.remove(current)
            current = current.getParentNode()
        }
    }

    @Override
    void onNodeInserted(NodeModel parent, NodeModel child, int newIndex) { invalidateAncestors(parent) }

    @Override
    void onNodeDeleted(NodeDeletionEvent event) { invalidateAncestors(event.parent) }

    @Override
    void onNodeMoved(NodeMoveEvent event) {
        invalidateAncestors(event.oldParent)
        invalidateAncestors(event.newParent)
    }
}

@CompileStatic
class FoldedCountDrawable implements InvocationHandler {
    private static final Color BAND_1 = Color.WHITE                     // 1 - 10
    private static final Color BAND_2 = new Color(0xF2, 0xC3, 0x00)     // 11 - 99
    private static final Color BAND_3 = new Color(0xEE, 0x7B, 0x00)     // 100 - 999
    private static final Color BAND_4 = new Color(0xD8, 0x2C, 0x1F)     // 1000 +
    private static final Color DARK_INK = new Color(0x1A, 0x1A, 0x1A)
    private static final Color OUTLINE = new Color(0, 0, 0, 90)

    private final MethodHandle originalDraw
    private final SubtreeSizes sizes
    private final Map<String, Font> fontCache = new HashMap<String, Font>()
    private final Map<Font, FontMetrics> metricsCache = new HashMap<Font, FontMetrics>()
    long nanos = 0, badges = 0, calls = 0

    FoldedCountDrawable(MethodHandle originalDraw, SubtreeSizes sizes) {
        this.originalDraw = originalDraw
        this.sizes = sizes
    }

    private static Color bandOf(int n) { n <= 10 ? BAND_1 : n <= 99 ? BAND_2 : n <= 999 ? BAND_3 : BAND_4 }

    private static String labelOf(int n, boolean compact) {
        if (!compact || n < 1000) return String.valueOf(n)
        if (n < 10000) return String.valueOf(Math.round(n / 100.0d) / 10.0d) + 'k'
        return String.valueOf((int) Math.round(n / 1000.0d)) + 'k'
    }

    private Font font(Font base, int size) {
        String key = base.getFamily() + '#' + size
        Font cached = fontCache.get(key)
        if (cached == null) {
            cached = base.deriveFont(Font.BOLD, (float) size)
            fontCache.put(key, cached)
        }
        return cached
    }

    private FontMetrics metrics(Graphics2D g, Font f) {
        FontMetrics cached = metricsCache.get(f)
        if (cached == null) {
            cached = g.getFontMetrics(f)
            metricsCache.put(f, cached)
        }
        return cached
    }

    @Override
    Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName()
        if (name != 'draw') {
            if (name == 'toString') return 'FoldedCountDrawable'
            if (name == 'hashCode') return Integer.valueOf(System.identityHashCode(proxy))
            if (name == 'equals') return Boolean.valueOf(proxy.is(args[0]))
            return null
        }
        long started = System.nanoTime()
        calls++
        Graphics2D g = (Graphics2D) args[0]
        NodeView nodeView = (NodeView) args[1]
        Rectangle mark = (Rectangle) args[2]
        try {
            if (!nodeView.isFolded()) {
                originalDraw.invokeWithArguments(g, nodeView, mark)
                return null
            }
            int count = sizes.of(nodeView.getNode())
            if (count <= 0) {
                originalDraw.invokeWithArguments(g, nodeView, mark)
                return null
            }
            // Rectangle.width/height read as double through Groovy properties: pin them to int
            int markX = (int) mark.getX(), markY = (int) mark.getY()
            int markW = (int) mark.getWidth(), markH = (int) mark.getHeight()
            // a mark as wide as it is tall is the hover control
            boolean hover = markW >= markH
            if (hover) originalDraw.invokeWithArguments(g, nodeView, mark)

            def mainView = nodeView.getMainView()
            int mainH = mainView.getHeight()
            Rectangle clip = g.getClipBounds()
            int clipRight = clip == null ? markX + 999 : (int) clip.getX() + (int) clip.getWidth() - 1

            // THE BADGE MUST FIT INSIDE THE FOLDING CONTROL RECTANGLE. Entering or leaving the node
            // repaints only that rectangle (MainView.setMouseArea:836 ->
            // paintFoldingRectangleImmediately:862 -> map.paintImmediately(foldingControlBounds)),
            // so anything drawn outside it is neither redrawn nor erased on hover: the badge
            // vanished under the mouse and left a ghost behind when the mouse left.
            // Staying inside also keeps the badge in the SAME place in both states, so there is no
            // jump. The price is width: long numbers get abbreviated, which is the accepted trade.
            Rectangle safe = mainView.getFoldingControlBounds()
            int safeX = (int) safe.getX(), safeY = (int) safe.getY()
            int safeW = (int) safe.getWidth(), safeH = (int) safe.getHeight()

            // MapView.paintSelecteds runs after every node and draws the selection rectangle with
            // a 4px gap AROUND the content (getRoundRectangleAround(selected, 4, 15)) -- it lands
            // on the badge's left edge and eats it. Screen pixels, not zoomed, and only on the
            // selected node, so step aside just there.
            int selectionGap = nodeView.isSelected() ? 6 : 0
            int badgeX = safeX + selectionGap
            int available = Math.min(safeX + safeW, clipRight) - badgeX
            if (available < 8) {
                if (!hover) originalDraw.invokeWithArguments(g, nodeView, mark)
                return null
            }
            Font nodeFont = mainView.getFont()
            int fontSize = Math.max(7, Math.min((int) (nodeFont.getSize() * 0.78d),
                    Math.min(mainH - 4, safeH - 4)))
            Font f = font(nodeFont, fontSize)
            FontMetrics fm = metrics(g, f)
            String label = labelOf(count, false)
            int textWidth = fm.stringWidth(label)
            if (textWidth + 6 > available) {
                label = labelOf(count, true)
                textWidth = fm.stringWidth(label)
                while (textWidth + 6 > available && fontSize > 7) {
                    fontSize--
                    f = font(nodeFont, fontSize)
                    fm = metrics(g, f)
                    textWidth = fm.stringWidth(label)
                }
            }
            int badgeW = Math.min(available, textWidth + 6)
            int badgeH = Math.min(Math.min(mainH - 2, safeH), fontSize + 4)
            int badgeY = safeY + (int) (safeH / 2) - (int) (badgeH / 2)

            Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setColor(bandOf(count))
            g.fillRoundRect(badgeX, badgeY, badgeW, badgeH, badgeH, badgeH)
            g.setColor(OUTLINE)
            g.drawRoundRect(badgeX, badgeY, badgeW, badgeH, badgeH, badgeH)
            g.setColor(count <= 999 ? DARK_INK : Color.WHITE)
            g.setFont(f)
            g.drawString(label, badgeX + (int) ((badgeW - textWidth) / 2),
                    badgeY + (int) ((badgeH - fm.getHeight()) / 2) + fm.getAscent())
            if (oldAA != null) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA)
            badges++
        } catch (Throwable t) {
            UIManager.put('foldedNodeCount.lastError', t.getClass().getName() + ': ' + t.getMessage())
        } finally {
            nanos += (System.nanoTime() - started)
        }
        return null
    }
}

// --- install / uninstall -------------------------------------------------
final String STATE_KEY = 'foldedNodeCount.state'

def classLoader = FoldingMark.class.getClassLoader()
def drawableClass = Class.forName('org.freeplane.view.swing.map.Drawable', true, classLoader)
def drawMethod = drawableClass.getDeclaredMethod('draw', Graphics2D.class, NodeView.class, Rectangle.class)
drawMethod.setAccessible(true)
def drawableField = FoldingMark.class.getDeclaredField('drawable')
drawableField.setAccessible(true)

def marks = [FoldingMark.FOLDING_CIRCLE_FOLDED, FoldingMark.FOLDING_CIRCLE_UNFOLDED]
def modeController = Controller.currentModeController
def mapController = modeController.getMapController()
def repaint = { ->
    def mapView = Controller.currentController.mapViewManager.mapViewComponent
    if (mapView != null) mapView.repaint()
}

def state = (Map) UIManager.get(STATE_KEY)
if (state != null) {
    Map originals = (Map) state.get('originals')
    marks.each { drawableField.set(it, originals.get(it.name())) }
    def listener = state.get('listener')
    if (listener != null) mapController.removeMapChangeListener((IMapChangeListener) listener)
    UIManager.put(STATE_KEY, null)
    repaint()
    return 'folded node count: OFF (original folding marks restored)'
}

def sizes = new SubtreeSizes()
mapController.addMapChangeListener(sizes)
Map originals = [:]
Map handlers = [:]
marks.each { originals.put(it.name(), drawableField.get(it)) }
marks.each { mark ->
    MethodHandle handle = MethodHandles.lookup().unreflect(drawMethod).bindTo(originals.get(mark.name()))
    def handler = new FoldedCountDrawable(handle, sizes)
    handlers.put(mark.name(), handler)
    drawableField.set(mark, Proxy.newProxyInstance(classLoader, [drawableClass] as Class[], handler))
}
UIManager.put(STATE_KEY, [originals: originals, listener: sizes, handlers: handlers, sizes: sizes])
repaint()

return 'folded node count: ON (run again to turn it off)'
