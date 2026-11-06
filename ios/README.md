# AI 주식 분석센터 PRO — iOS (iPhone)

안드로이드판(`app/`)과 같은 기능을 Swift/SwiftUI 로 새로 만든 아이폰 전용 앱입니다. 안드로이드 코드는 변경하지 않습니다.

- `AIStockCore/` — 분석 엔진·지표·추천·시세(네이버/Yahoo/Finnhub)·뉴스·전체 종목 목록·저장소 (Swift Package, 단위 테스트 포함)
- `AIStockPro/` — SwiftUI 앱 (홈 · 종목 분석 · 추천 BEST 5 · 관심 종목 · 설정), 아이콘, 실행 화면
- `project.yml` — XcodeGen 프로젝트 정의 (`xcodegen generate` → `AIStockPro.xcodeproj`)
- `docs/01_아이폰_설치_설명서.md` — Apple Developer 가입·TestFlight 배포·설치 방법
- `docs/screenshots/` — CI 시뮬레이터 실행 화면

빌드: GitHub Actions `iOS 빌드·테스트` (macOS 러너). Apple 시크릿 등록 시 TestFlight 자동 업로드.
