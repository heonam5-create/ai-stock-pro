package com.aistock.pro.data

/**
 * 한글 종목명 검색용 내장 사전 (오프라인).
 * - 한국: 6자리 코드 + 거래소(.KS 코스피 / .KQ 코스닥)
 * - 미국: 한글 별칭 → 티커
 * 사전에 없는 종목은 (1) 6자리 코드 직접 입력, (2) 영문명/티커 온라인 검색,
 * (3) 공공데이터포털 키 등록 시 한국 전 종목 한글명 검색으로 찾을 수 있습니다.
 * 상장시장 이전 등으로 .KS/.KQ 가 바뀐 경우 Repository 가 자동으로 반대 시장을 재시도합니다.
 */
object StockDirectory {

    data class Entry(val symbol: String, val nameKr: String, val nameEn: String, val aliases: List<String> = emptyList()) {
        val market: Market get() = if (symbol.endsWith(".KS") || symbol.endsWith(".KQ")) Market.KR else Market.US
        val exchange: String get() = when {
            symbol.endsWith(".KS") -> "코스피"
            symbol.endsWith(".KQ") -> "코스닥"
            else -> "미국"
        }
        fun toRef() = StockRef(symbol, nameEn, nameKr, exchange, market)
    }

    private fun kr(code: String, mkt: String, name: String, vararg alias: String) =
        Entry("$code.$mkt", name, name, alias.toList())

    private fun us(ticker: String, kr: String, en: String, vararg alias: String) =
        Entry(ticker, kr, en, alias.toList())

    val entries: List<Entry> = listOf(
        // ── 코스피 ──
        kr("005930", "KS", "삼성전자", "삼전", "samsung electronics"),
        kr("000660", "KS", "SK하이닉스", "하이닉스", "sk hynix"),
        kr("373220", "KS", "LG에너지솔루션", "엘지에너지솔루션", "엘솔"),
        kr("207940", "KS", "삼성바이오로직스", "삼바"),
        kr("005380", "KS", "현대차", "현대자동차", "hyundai motor"),
        kr("000270", "KS", "기아", "기아차", "kia"),
        kr("068270", "KS", "셀트리온", "celltrion"),
        kr("005490", "KS", "POSCO홀딩스", "포스코홀딩스", "포스코"),
        kr("035420", "KS", "NAVER", "네이버", "naver"),
        kr("035720", "KS", "카카오", "kakao"),
        kr("051910", "KS", "LG화학", "엘지화학"),
        kr("006400", "KS", "삼성SDI", "삼성에스디아이"),
        kr("105560", "KS", "KB금융", "kb금융지주"),
        kr("055550", "KS", "신한지주", "신한금융"),
        kr("086790", "KS", "하나금융지주", "하나금융"),
        kr("316140", "KS", "우리금융지주", "우리금융"),
        kr("138040", "KS", "메리츠금융지주", "메리츠금융"),
        kr("024110", "KS", "기업은행", "ibk기업은행"),
        kr("012330", "KS", "현대모비스"),
        kr("028260", "KS", "삼성물산"),
        kr("066570", "KS", "LG전자", "엘지전자"),
        kr("003550", "KS", "LG", "엘지"),
        kr("003670", "KS", "포스코퓨처엠"),
        kr("096770", "KS", "SK이노베이션"),
        kr("034730", "KS", "SK", "에스케이"),
        kr("402340", "KS", "SK스퀘어"),
        kr("017670", "KS", "SK텔레콤", "skt"),
        kr("030200", "KS", "KT", "케이티"),
        kr("015760", "KS", "한국전력", "한전"),
        kr("032830", "KS", "삼성생명"),
        kr("000810", "KS", "삼성화재"),
        kr("029780", "KS", "삼성카드"),
        kr("016360", "KS", "삼성증권"),
        kr("018260", "KS", "삼성에스디에스", "삼성sds"),
        kr("009150", "KS", "삼성전기"),
        kr("010140", "KS", "삼성중공업"),
        kr("028050", "KS", "삼성E&A", "삼성엔지니어링"),
        kr("323410", "KS", "카카오뱅크"),
        kr("377300", "KS", "카카오페이"),
        kr("259960", "KS", "크래프톤"),
        kr("352820", "KS", "하이브", "hybe"),
        kr("036570", "KS", "엔씨소프트", "엔씨"),
        kr("251270", "KS", "넷마블"),
        kr("010130", "KS", "고려아연"),
        kr("011200", "KS", "HMM"),
        kr("012450", "KS", "한화에어로스페이스", "한화에어로"),
        kr("042660", "KS", "한화오션"),
        kr("000880", "KS", "한화"),
        kr("272210", "KS", "한화시스템"),
        kr("009540", "KS", "HD한국조선해양", "한국조선해양"),
        kr("329180", "KS", "HD현대중공업", "현대중공업"),
        kr("267260", "KS", "HD현대일렉트릭", "현대일렉트릭"),
        kr("034020", "KS", "두산에너빌리티"),
        kr("241560", "KS", "두산밥캣"),
        kr("064350", "KS", "현대로템"),
        kr("047810", "KS", "한국항공우주", "kai"),
        kr("079550", "KS", "LIG넥스원"),
        kr("042700", "KS", "한미반도체"),
        kr("011070", "KS", "LG이노텍"),
        kr("010120", "KS", "LS ELECTRIC", "ls일렉트릭"),
        kr("006260", "KS", "LS"),
        kr("103140", "KS", "풍산"),
        kr("033780", "KS", "KT&G"),
        kr("090430", "KS", "아모레퍼시픽"),
        kr("051900", "KS", "LG생활건강"),
        kr("097950", "KS", "CJ제일제당"),
        kr("001040", "KS", "CJ"),
        kr("011170", "KS", "롯데케미칼"),
        kr("023530", "KS", "롯데쇼핑"),
        kr("004170", "KS", "신세계"),
        kr("139480", "KS", "이마트"),
        kr("069960", "KS", "현대백화점"),
        kr("282330", "KS", "BGF리테일"),
        kr("021240", "KS", "코웨이"),
        kr("302440", "KS", "SK바이오사이언스"),
        kr("326030", "KS", "SK바이오팜"),
        kr("000100", "KS", "유한양행"),
        kr("128940", "KS", "한미약품"),
        kr("003490", "KS", "대한항공"),
        kr("180640", "KS", "한진칼"),
        kr("010950", "KS", "S-Oil", "에쓰오일", "s-oil"),
        kr("004020", "KS", "현대제철"),
        kr("000720", "KS", "현대건설"),
        kr("161390", "KS", "한국타이어앤테크놀로지", "한국타이어"),
        kr("078930", "KS", "GS"),
        kr("047050", "KS", "포스코인터내셔널"),
        kr("006800", "KS", "미래에셋증권"),
        kr("071050", "KS", "한국금융지주"),
        kr("005940", "KS", "NH투자증권"),
        kr("039490", "KS", "키움증권"),
        kr("005830", "KS", "DB손해보험"),
        kr("088980", "KS", "맥쿼리인프라"),
        kr("454910", "KS", "두산로보틱스"),
        kr("450080", "KS", "에코프로머티"),
        // ── 코스닥 ──
        kr("247540", "KQ", "에코프로비엠"),
        kr("086520", "KQ", "에코프로"),
        kr("196170", "KQ", "알테오젠"),
        kr("028300", "KQ", "HLB", "에이치엘비"),
        kr("263750", "KQ", "펄어비스"),
        kr("293490", "KQ", "카카오게임즈"),
        kr("035900", "KQ", "JYP Ent.", "jyp", "제이와이피"),
        kr("041510", "KQ", "에스엠", "sm엔터"),
        kr("122870", "KQ", "와이지엔터테인먼트", "yg"),
        kr("058470", "KQ", "리노공업"),
        kr("240810", "KQ", "원익IPS"),
        kr("357780", "KQ", "솔브레인"),
        kr("039030", "KQ", "이오테크닉스"),
        kr("095340", "KQ", "ISC", "아이에스시"),
        kr("214150", "KQ", "클래시스"),
        kr("145020", "KQ", "휴젤"),
        kr("141080", "KQ", "리가켐바이오"),
        kr("068760", "KQ", "셀트리온제약"),
        kr("112040", "KQ", "위메이드"),
        kr("277810", "KQ", "레인보우로보틱스"),
        kr("403870", "KQ", "HPSP"),
        kr("036930", "KQ", "주성엔지니어링"),
        kr("348370", "KQ", "엔켐"),
        kr("253450", "KQ", "스튜디오드래곤"),
        kr("067160", "KQ", "SOOP", "아프리카tv", "숲"),

        // ── 미국 ──
        us("NVDA", "엔비디아", "NVIDIA", "nvidia"),
        us("AAPL", "애플", "Apple", "apple"),
        us("MSFT", "마이크로소프트", "Microsoft", "마소", "microsoft"),
        us("AMZN", "아마존", "Amazon", "amazon"),
        us("GOOGL", "알파벳", "Alphabet", "구글", "google", "alphabet"),
        us("META", "메타", "Meta Platforms", "페이스북", "meta"),
        us("TSLA", "테슬라", "Tesla", "tesla"),
        us("AVGO", "브로드컴", "Broadcom", "broadcom"),
        us("MU", "마이크론", "Micron Technology", "micron", "마이크론테크놀로지"),
        us("SNDK", "샌디스크", "Sandisk", "sandisk"),
        us("WDC", "웨스턴디지털", "Western Digital"),
        us("STX", "시게이트", "Seagate Technology"),
        us("AMD", "AMD", "Advanced Micro Devices", "에이엠디"),
        us("INTC", "인텔", "Intel", "intel"),
        us("QCOM", "퀄컴", "Qualcomm"),
        us("TSM", "TSMC", "Taiwan Semiconductor", "티에스엠씨", "대만반도체"),
        us("ASML", "ASML", "ASML Holding"),
        us("ARM", "ARM", "Arm Holdings", "암홀딩스"),
        us("SMCI", "슈퍼마이크로컴퓨터", "Super Micro Computer", "슈마컴", "슈퍼마이크로"),
        us("DELL", "델", "Dell Technologies"),
        us("LRCX", "램리서치", "Lam Research"),
        us("AMAT", "어플라이드머티어리얼즈", "Applied Materials"),
        us("KLAC", "KLA", "KLA Corporation"),
        us("TXN", "텍사스인스트루먼트", "Texas Instruments"),
        us("NFLX", "넷플릭스", "Netflix"),
        us("PLTR", "팔란티어", "Palantir Technologies", "palantir"),
        us("ORCL", "오라클", "Oracle"),
        us("CRM", "세일즈포스", "Salesforce"),
        us("ADBE", "어도비", "Adobe"),
        us("CRWD", "크라우드스트라이크", "CrowdStrike"),
        us("SNOW", "스노우플레이크", "Snowflake"),
        us("COST", "코스트코", "Costco"),
        us("WMT", "월마트", "Walmart"),
        us("BRK-B", "버크셔해서웨이", "Berkshire Hathaway B", "버크셔"),
        us("JPM", "JP모건", "JPMorgan Chase", "제이피모건"),
        us("BAC", "뱅크오브아메리카", "Bank of America"),
        us("GS", "골드만삭스", "Goldman Sachs"),
        us("V", "비자", "Visa"),
        us("MA", "마스터카드", "Mastercard"),
        us("LLY", "일라이릴리", "Eli Lilly", "릴리"),
        us("NVO", "노보노디스크", "Novo Nordisk"),
        us("UNH", "유나이티드헬스", "UnitedHealth Group"),
        us("JNJ", "존슨앤드존슨", "Johnson & Johnson", "존슨앤존슨"),
        us("PFE", "화이자", "Pfizer"),
        us("KO", "코카콜라", "Coca-Cola"),
        us("PEP", "펩시코", "PepsiCo", "펩시"),
        us("MCD", "맥도날드", "McDonald's"),
        us("NKE", "나이키", "Nike"),
        us("DIS", "디즈니", "Walt Disney"),
        us("SBUX", "스타벅스", "Starbucks"),
        us("HD", "홈디포", "Home Depot"),
        us("BA", "보잉", "Boeing"),
        us("XOM", "엑슨모빌", "Exxon Mobil"),
        us("CVX", "셰브론", "Chevron"),
        us("UBER", "우버", "Uber Technologies"),
        us("ABNB", "에어비앤비", "Airbnb"),
        us("SHOP", "쇼피파이", "Shopify"),
        us("COIN", "코인베이스", "Coinbase"),
        us("MSTR", "스트래티지", "Strategy (MicroStrategy)", "마이크로스트래티지"),
        us("HOOD", "로빈후드", "Robinhood Markets"),
        us("SOFI", "소파이", "SoFi Technologies"),
        us("IONQ", "아이온큐", "IonQ"),
        us("RGTI", "리게티컴퓨팅", "Rigetti Computing", "리게티"),
        us("RIVN", "리비안", "Rivian Automotive"),
        us("LCID", "루시드", "Lucid Group"),
        us("NIO", "니오", "NIO"),
        us("BABA", "알리바바", "Alibaba"),
        us("CPNG", "쿠팡", "Coupang"),
        us("SPY", "S&P500 ETF", "SPDR S&P 500 ETF", "에스앤피500"),
        us("QQQ", "나스닥100 ETF", "Invesco QQQ", "나스닥100"),
        us("SOXX", "반도체 ETF", "iShares Semiconductor ETF"),
    )

    private fun norm(s: String) = s.lowercase().replace(" ", "").replace("-", "").replace(".", "").replace("&", "")

    /** 내장 사전 검색 (정확일치 > 앞부분 일치 > 포함) */
    fun search(query: String, limit: Int = 15): List<Entry> {
        val q = norm(query)
        if (q.isEmpty()) return emptyList()
        val scored = entries.mapNotNull { e ->
            val keys = listOf(e.nameKr, e.nameEn, e.symbol.substringBefore("."), e.symbol) + e.aliases
            var best = 0
            for (k in keys) {
                val nk = norm(k)
                val s = when {
                    nk == q -> 100
                    nk.startsWith(q) -> 80
                    nk.contains(q) -> 60
                    else -> 0
                }
                if (s > best) best = s
            }
            if (best > 0) e to best else null
        }
        return scored.sortedWith(compareByDescending<Pair<Entry, Int>> { it.second }.thenBy { it.first.nameKr.length })
            .take(limit).map { it.first }
    }

    fun bySymbol(symbol: String): Entry? = entries.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) }

    fun byKrCode(code: String): Entry? = entries.firstOrNull { it.market == Market.KR && it.symbol.startsWith("$code.") }

    fun koreanNameFor(symbol: String): String? = bySymbol(symbol)?.nameKr

    /** BEST 5 후보군 (거래량·시가총액 상위 위주, 후보군은 앱 업데이트로 조정 가능) */
    val krUniverse = listOf(
        "005930.KS", "000660.KS", "373220.KS", "207940.KS", "005380.KS", "000270.KS", "068270.KS",
        "035420.KS", "035720.KS", "051910.KS", "006400.KS", "105560.KS", "055550.KS", "012330.KS",
        "028260.KS", "066570.KS", "012450.KS", "042660.KS", "009540.KS", "329180.KS", "267260.KS",
        "034020.KS", "064350.KS", "042700.KS", "402340.KS", "010130.KS", "259960.KS", "352820.KS",
        "009150.KS", "011070.KS", "247540.KQ", "196170.KQ", "086520.KQ", "028300.KQ", "058470.KQ", "277810.KQ",
    )
    val usUniverse = listOf(
        "NVDA", "AAPL", "MSFT", "AMZN", "GOOGL", "META", "TSLA", "AVGO", "MU", "SNDK", "AMD", "TSM",
        "ASML", "NFLX", "PLTR", "ORCL", "CRM", "COST", "WMT", "JPM", "V", "LLY", "UNH", "XOM",
        "UBER", "SHOP", "COIN", "ARM", "SMCI", "LRCX", "AMAT", "WDC", "STX", "CRWD", "IONQ", "HOOD",
    )
}
