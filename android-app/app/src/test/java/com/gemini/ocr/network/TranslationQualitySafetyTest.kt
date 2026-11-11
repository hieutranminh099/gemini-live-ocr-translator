package com.gemini.ocr.network

import org.junit.Assert.*
import org.junit.Test

/** Synthetic source/translation pairs; these test cache safety, not Gemini quality or speed. */
class TranslationQualitySafetyTest {
    private fun match(source: String, cached: String, translated: String) =
        CostOptimizationEngine.findFuzzyMatch(source, mapOf("zh|manga|fast|$cached" to translated))

    @Test fun aSingleNegationCannotReuseTheOppositeMeaning() {
        val positive = "我今天一定会答应你的请求"
        val negative = "我今天一定不会答应你的请求"
        assertTrue(CostOptimizationEngine.stringSimilarity(negative, positive) >= .90)
        assertNull(match(negative, positive, "Hôm nay tôi nhất định sẽ đồng ý với yêu cầu của anh."))
    }

    @Test fun aChangedPersonCannotReuseThePreviousPersonsTranslation() {
        assertNull(match("我已经告诉李明明天在车站等你", "我已经告诉王明明天在车站等你",
            "Tôi đã bảo Vương Minh đợi anh ở ga vào ngày mai."))
        assertNull(match("我明天一定会带着他一起离开这里", "我明天一定会带着你一起离开这里",
            "Ngày mai tôi nhất định sẽ đưa anh cùng rời khỏi đây."))
    }

    @Test fun chineseNumeralChangesCannotBeTreatedAsOcrNoise() {
        assertNull(match("这批货物一共需要五千块钱才能买下", "这批货物一共需要三千块钱才能买下",
            "Phải mất tổng cộng ba nghìn tệ mới mua được lô hàng này."))
    }

    @Test fun numberReplacementIsSimultaneousRatherThanCascading() {
        val result = match("还有2小时3分钟", "还有1小时2分钟", "Còn 1 giờ và 2 phút.")
        assertNotNull(result)
        assertEquals("Còn 2 giờ và 3 phút.", result!!.translation)
        assertTrue(result.templateMatched)
    }

    @Test fun numberReplacementFollowsValuesWhenVietnameseReordersThem() {
        val result = match("等级2获得3金币", "等级1获得2金币", "Nhận 2 đồng vàng ở cấp 1.")
        assertEquals("Nhận 3 đồng vàng ở cấp 2.", result!!.translation)
    }

    @Test fun ambiguousRepeatedNumberCannotReuseTheOldTranslation() {
        assertNull(match("2人各拿3张票", "1人各拿1张票", "1 người, mỗi người lấy 1 vé."))
    }

    @Test fun replacementCannotMatchPartOfALargerTargetNumberOrOmitAValue() {
        assertNull(match("获得2枚金币", "获得1枚金币", "Nhận được 10 đồng vàng."))
        assertNull(match("还有2小时3分钟", "还有1小时2分钟", "Còn một giờ và hai phút."))
    }

    @Test fun numbersDoNotPermitOtherLexicalChanges() {
        assertNull(match("获得250金币后不要离开这里", "获得100金币后就要离开这里",
            "Sau khi nhận 100 đồng vàng thì phải rời khỏi đây."))
    }

    @Test fun harmlessWhitespaceAndTraditionalVariantsCanStillReuse() {
        assertEquals("Còn 250 linh thạch", match("获得  250 靈石", "获得 100 灵石", "Còn 100 linh thạch")!!.translation)
    }

    @Test fun questionAndStatementPunctuationAreNotInterchangeable() {
        assertNull(match("你真的已经决定要离开这里了吗？", "你真的已经决定要离开这里了吗！",
            "Anh thực sự đã quyết định rời khỏi đây rồi!"))
    }

    @Test fun anObjectPronounChangeCannotBeHiddenByALongSharedPrefix() {
        assertNull(match("我已经说过这件事情只需要告诉她", "我已经说过这件事情只需要告诉他",
            "Tôi đã nói rồi, chuyện này chỉ cần báo cho anh ấy."))
        assertNull(match("我早就答应过今天一定不会离开这里", "我早就答应过今天一定会离开这里",
            "Tôi đã hứa từ lâu rằng hôm nay nhất định sẽ rời khỏi đây."))
    }

    @Test fun identicalRepeatedNumberCanBeReplacedWithoutGuessingAlignment() {
        val result = match("2人各拿2张票", "1人各拿1张票", "1 người, mỗi người lấy 1 vé.")
        assertEquals("2 người, mỗi người lấy 2 vé.", result!!.translation)
    }

    @Test fun wholeDecimalTokensAreSubstitutedInOnePass() {
        val result = match("需要1.5小时支付2.5元", "需要0.5小时支付1.5元",
            "Cần 0.5 giờ và trả 1.5 tệ.")
        assertEquals("Cần 1.5 giờ và trả 2.5 tệ.", result!!.translation)
    }

    @Test fun targetNumberOmissionOrAnExtraNumberRequiresFreshTranslation() {
        assertNull(match("2人买3张票", "1人买2张票", "Mua 2 vé."))
        assertNull(match("2人买3张票", "1人买2张票", "1 người mua 2 vé với giá 10 tệ."))
    }

    @Test fun anUnchangedNumberCannotLicenseAnUnrelatedPersonOrAction() {
        assertNull(match("明天12点一定要把这份文件交给她", "明天12点一定要把这份文件交给他",
            "Ngày mai lúc 12 giờ nhất định phải đưa tài liệu này cho anh ấy."))
    }
}
