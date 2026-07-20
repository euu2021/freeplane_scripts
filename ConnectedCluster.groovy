def n = node
def inCons = n.connectorsIn //collection of connectors pointing into the node
def outCons = n.connectorsOut //collection of connectors pointing out of the node
def totCons = inCons + outCons // all the node's connectors
c.select(totCons.target + totCons.source)
