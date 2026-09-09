package com.fantamomo.hc.dns.util

data class DestructuringComponent<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10>(
    val t1: T1,
    val t2: T2,
    val t3: T3,
    val t4: T4,
    val t5: T5,
    val t6: T6,
    val t7: T7,
    val t8: T8,
    val t9: T9,
    val t10: T10
) {
    @Suppress("NOTHING_TO_INLINE")
    companion object {
        val EMPTY: DestructuringComponent0 = invoke(null)

        inline operator fun invoke() = EMPTY
        inline operator fun <T1> invoke(t1: T1) = invoke(t1, null)
        inline operator fun <T1, T2> invoke(t1: T1, t2: T2) = invoke(t1, t2, null)
        inline operator fun <T1, T2, T3> invoke(t1: T1, t2: T2, t3: T3) = invoke(t1, t2, t3, null)
        inline operator fun <T1, T2, T3, T4> invoke(t1: T1, t2: T2, t3: T3, t4: T4) = invoke(t1, t2, t3, t4, null)
        inline operator fun <T1, T2, T3, T4, T5> invoke(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5) = invoke(t1, t2, t3, t4, t5, null)
        inline operator fun <T1, T2, T3, T4, T5, T6> invoke(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6) = invoke(t1, t2, t3, t4, t5, t6, null)
        inline operator fun <T1, T2, T3, T4, T5, T6, T7> invoke(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7) = invoke(t1, t2, t3, t4, t5, t6, t7, null)
        inline operator fun <T1, T2, T3, T4, T5, T6, T7, T8> invoke(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8) = invoke(t1, t2, t3, t4, t5, t6, t7, t8, null)
        inline operator fun <T1, T2, T3, T4, T5, T6, T7, T8, T9> invoke(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9) = invoke(t1, t2, t3, t4, t5, t6, t7, t8, t9, null)
        inline operator fun <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10> invoke(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10): DestructuringComponent<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10> = DestructuringComponent(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10)
    }
}

typealias DestructuringComponent0 = DestructuringComponent1<Nothing?>
typealias DestructuringComponent1<T1> = DestructuringComponent2<T1, Nothing?>
typealias DestructuringComponent2<T1, T2> = DestructuringComponent3<T1, T2, Nothing?>
typealias DestructuringComponent3<T1, T2, T3> = DestructuringComponent4<T1, T2, T3, Nothing?>
typealias DestructuringComponent4<T1, T2, T3, T4> = DestructuringComponent5<T1, T2, T3, T4, Nothing?>
typealias DestructuringComponent5<T1, T2, T3, T4, T5> = DestructuringComponent6<T1, T2, T3, T4, T5, Nothing?>
typealias DestructuringComponent6<T1, T2, T3, T4, T5, T6> = DestructuringComponent7<T1, T2, T3, T4, T5, T6, Nothing?>
typealias DestructuringComponent7<T1, T2, T3, T4, T5, T6, T7> = DestructuringComponent8<T1, T2, T3, T4, T5, T6, T7, Nothing?>
typealias DestructuringComponent8<T1, T2, T3, T4, T5, T6, T7, T8> = DestructuringComponent9<T1, T2, T3, T4, T5, T6, T7, T8, Nothing?>
typealias DestructuringComponent9<T1, T2, T3, T4, T5, T6, T7, T8, T9> = DestructuringComponent<T1, T2, T3, T4, T5, T6, T7, T8, T9, Nothing?>
