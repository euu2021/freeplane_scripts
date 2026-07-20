// SPDX-License-Identifier: CC0-1.0

c.select(c.selecteds.collect{it.children.findAll{it.visible}}.flatten())
