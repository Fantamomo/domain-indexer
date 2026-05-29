package com.fantamomo.hc.dns.model

import com.fantamomo.hc.dns.model.dns.RecordKey
import com.fantamomo.hc.dns.model.dns.RecordTimeline

data class IndexResult(
    val records: Map<RecordKey, RecordTimeline>
)