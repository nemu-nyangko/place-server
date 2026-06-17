# update-plans

`Tasks/*.md` 파일들의 현재 상태를 읽어 `docs/PLANS.md`를 동기화합니다.

## 수행 절차

1. `docs/PLANS.md`를 읽어 현재 표 내용을 확인합니다.
2. `Tasks/` 디렉토리의 모든 `.md` 파일(`_template.md` 제외)을 읽어 각 task의 상태를 파악합니다.
3. PLANS.md 표의 상태(`todo` / `in-progress` / `blocked` / `done`)를 실제 Task 파일 상태에 맞게 갱신합니다.
4. 변경된 항목이 있으면 작업 로그에 오늘 날짜로 변경 내역을 추가합니다.
5. 변경 사항을 한 줄씩 보고합니다. 변경이 없으면 "이미 최신 상태입니다"라고 출력합니다.
