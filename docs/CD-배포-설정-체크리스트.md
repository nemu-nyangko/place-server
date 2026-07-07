# CD 배포 설정 체크리스트

원격 서버(VPS) + Docker + SSH 방식 CD를 동작시키기 위해 **직접 해야 할 일**을 정리한 문서입니다.
GitHub Actions 워크플로(`.github/workflows/cd.yml`)와 배포 파일은 이미 준비되어 있으며, 아래 항목만 채우면 배포가 자동화됩니다.

## 전체 흐름

```
master에 merge → 이미지 빌드 → GHCR push → SSH 접속 → docker compose pull & up -d
```

관련 파일:

| 파일 | 역할 |
|------|------|
| `Dockerfile` | Java 21 멀티스테이지 빌드 (비루트 실행) |
| `.dockerignore` | 빌드 컨텍스트 최소화 |
| `docker-compose.prod.yml` | 서버에서 실행되는 배포용 compose (app + redis) |
| `.github/workflows/cd.yml` | 빌드·push·SSH 배포 자동화 |

---

## 1. GitHub Secrets 등록 (필수)

`Settings → Secrets and variables → Actions → New repository secret` 또는 `gh` CLI로 등록합니다.

| Secret 이름 | 값 | 필수 |
|-------------|-----|------|
| `SSH_HOST` | 배포 서버 IP 또는 도메인 | ✅ |
| `SSH_USER` | 서버 SSH 접속 계정명 | ✅ |
| `SSH_KEY` | SSH 개인키 전체 내용 (`-----BEGIN ...` 포함) | ✅ |
| `SSH_PORT` | SSH 포트 (기본 22가 아닐 때만) | 선택 |
| `OPENAI_API_KEY` | OpenAI API 키 (AI 리터칭 사용 시) | 선택 |

`gh` CLI 예시:

```bash
gh secret set SSH_HOST -b "서버_IP"
gh secret set SSH_USER -b "접속_계정명"
gh secret set SSH_KEY < ~/.ssh/id_rsa
gh secret set OPENAI_API_KEY -b "sk-..."
# 포트가 22가 아니면
gh secret set SSH_PORT -b "2222"
```

> `GITHUB_TOKEN`은 GitHub가 자동 제공하므로 GHCR push/pull용으로 별도 등록하지 않아도 됩니다.

- [ ] `SSH_HOST` 등록
- [ ] `SSH_USER` 등록
- [ ] `SSH_KEY` 등록
- [ ] (필요 시) `SSH_PORT` 등록
- [ ] (필요 시) `OPENAI_API_KEY` 등록

---

## 2. 서버 준비 (한 번만)

서버에 SSH로 접속해 아래를 수행합니다. (Ubuntu 기준)

```bash
# Docker + compose 플러그인 설치
curl -fsSL https://get.docker.com | sh

# SSH 접속 계정이 sudo 없이 docker를 쓰도록 그룹 추가 (추가 후 재로그인 필요)
sudo usermod -aG docker $USER
```

그 외:

- [ ] Docker 및 `docker compose` 설치 확인 (`docker compose version`)
- [ ] SSH 접속 계정이 docker 명령 실행 가능 (`docker ps`가 sudo 없이 동작)
- [ ] 서버 방화벽 / 클라우드 보안 그룹에서 **8080 포트 인바운드 허용**
- [ ] GitHub Actions에 등록한 SSH 개인키에 대응하는 **공개키가 서버 `~/.ssh/authorized_keys`에 등록**되어 있는지 확인

> 배포 디렉터리(`~/place-server`)와 `docker-compose.prod.yml`은 배포 시 자동으로 서버에 복사되므로 직접 만들 필요 없습니다.

---

## 3. 배포 파일 커밋 & 실행

- [ ] `Dockerfile`, `.dockerignore`, `docker-compose.prod.yml`, `.github/workflows/cd.yml` 커밋
- [ ] `develop`을 거쳐 **`master`에 merge** → 배포 자동 실행
- [ ] 또는 Actions 탭에서 `CD` 워크플로 → **Run workflow**로 수동 실행

---

## 4. 배포 확인

- [ ] Actions 탭에서 `CD` 워크플로가 성공(초록)인지 확인
- [ ] 서버에서 컨테이너 상태 확인: `docker compose -f ~/place-server/docker-compose.prod.yml ps`
- [ ] 앱 응답 확인: `curl http://<서버_IP>:8080/api/canvas` (또는 헬스 엔드포인트)

---

## 참고 / 향후 옵션

- **배포 트리거**: 현재 `master` push 기준(프로덕션 = master). `develop` merge 시마다 배포하려면 `cd.yml`의 `branches` 수정.
- **HTTPS / 도메인**: 현재 8080 포트 직접 노출. 운영 시 Nginx 리버스 프록시 + Let's Encrypt 구성 권장.
- **이미지 태그**: `latest`와 커밋 SHA 두 태그로 push되므로, 롤백 시 특정 SHA 이미지로 되돌릴 수 있음.
