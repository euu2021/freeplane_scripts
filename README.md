# freeplane_scripts

Groovy scripts for [Freeplane](https://www.freeplane.org/), by [euu2021](https://github.com/euu2021).

Each `.groovy` file is a standalone script. To use one, drop it into your Freeplane user scripts directory (or add its folder under *Preferences → Plugins → Scripting → Script directories*), restart Freeplane, and bind it to a menu entry or keyboard shortcut. Scripts whose header starts with `//init` are init scripts (listeners), loaded automatically at startup.

## Scripts

- **duplicateNode.groovy** — Duplicates the selected node(s), placing each copy right below its original.
- **selectLevel1Children.groovy** — Selects the visible first-level children of the selected nodes.
- **navigationLimitedToSiblingsDown.groovy** / **navigationLimitedToSiblingsUP.groovy** — Moves the selection only among sibling nodes (down / up), without leaving the current level.
- **selectionLimitedToSiblingsDown.groovy** / **selectionLimitedToSiblingsUp.groovy** — Extends the selection only among sibling nodes (down / up).
- **safeDeleteListener.groovy** — Init listener that guards against accidental node deletion.
- **initScriptsTool.groovy** — Utility that runs the init scripts found in the script directory.
- **SearchPanel.groovy** — Cross-cutting search panel (one term matches the node, the others its ancestors), drawing lines from each result to its node on the map.
- **UtilityPanels.groovy** — Collection of utility panels over the map (quick search, recent nodes, in-place siblings preview, and more), v1.48.

## License

CC0-1.0 — these scripts are released into the public domain (see `LICENSE`). No rights reserved; use them however you like.
