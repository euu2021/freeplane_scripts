// SPDX-License-Identifier: MIT
// Discussion thread: https://github.com/freeplane/freeplane/discussions/2375

import org.freeplane.api.MindMap as ApiMindMap
import org.freeplane.plugin.script.proxy.NodeProxy as ProxyNode
import org.freeplane.features.map.MapModel;
import org.freeplane.features.map.NodeModel;
import org.freeplane.features.mode.Controller;
import org.freeplane.features.styles.MapStyleModel;
import org.freeplane.plugin.script.ScriptContext
import org.freeplane.plugin.script.proxy.ScriptUtils

def userStylesParentNode = getUserDefinedStylesParentNode()

styleNodeToInsertConditionalStyle = userStylesParentNode.children.find{it.text == 'ex1'} //replace `ex1` with the name of the style

styleNodeToInsertConditionalStyle.conditionalStyles.add(
        true, // isActive
        "node.text == 'Sunday'", // script
        'ex2', // styleName
        false) // isLast (aka stop)

///////// methods definitions ///////

def static getUserDefinedStylesParentNode(x = null){
    return getUserDefinedStylesParentNode((ScriptContext) null)
}

def static getUserDefinedStylesParentNode(ScriptContext scriptContext){
    MapModel mapa = Controller.getCurrentController().getMap();
    return getUserDefinedStylesParentNode(mapa, scriptContext)
}

def static getUserDefinedStylesParentNode(ApiMindMap mapaProxy, ScriptContext scriptContext){
    return getUserDefinedStylesParentNode(mapaProxy.delegate, scriptContext)
}

def static getUserDefinedStylesParentNode(MapModel mapa, ScriptContext scriptContext){
    if(!mapa) {
        return getUserDefinedStylesParentNode(scriptContext)
    }
    MapStyleModel styleModel = MapStyleModel.getExtension(mapa);
    MapModel styleMap = styleModel.getStyleMap();
    NodeModel userStyleParentNode = styleModel.getStyleNodeGroup(styleMap, MapStyleModel.STYLES_USER_DEFINED);
    def userDefinedParentNode = new ProxyNode(userStyleParentNode, scriptContext)
    return userDefinedParentNode
}
