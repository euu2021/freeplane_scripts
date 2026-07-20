// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: MIT

c.select(c.selecteds.collect{it.children.findAll{it.visible}}.flatten())
