package eu.kanade.tachiyomi.source

import tachiyomi.domain.source.model.StubSource
import tachiyomi.source.local.isLocal

fun Source.isLocalOrStub(): Boolean = isLocal() || this is StubSource
