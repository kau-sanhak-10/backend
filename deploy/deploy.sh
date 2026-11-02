#!/usr/bin/env bash
# EC2에서 실행 (GitHub Actions → SSM, root 권한)
# 새 이미지로 컨테이너를 바꾸고, health가 90초 안에 UP이 안 되면 직전 이미지로 되돌림
# 사용: deploy.sh <dev|prod> <ECR 이미지 주소:태그>
set -euo pipefail
umask 077

ENV_NAME="${1:?dev 또는 prod}"
IMAGE="${2:?이미지 주소}"
REGION=ap-northeast-2

case "$ENV_NAME" in
  dev)  PORT=8081 ;;
  prod) PORT=8080 ;;
  *) echo "알 수 없는 환경: $ENV_NAME" >&2; exit 2 ;;
esac

NAME="ilro-$ENV_NAME"
ENV_FILE="/run/ilro/$ENV_NAME.env"

# 같은 환경 배포가 동시에 돌지 않게 (먼저 온 배포가 끝날 때까지 대기)
mkdir -p /run/ilro
exec 9>"/run/ilro/deploy-$ENV_NAME.lock"
flock 9

# 1. 비밀값: Parameter Store /ilro/<환경>/ → env 파일 (메모리 폴더, root만 읽기)
aws ssm get-parameters-by-path --path "/ilro/$ENV_NAME" --with-decryption --region "$REGION" \
  --query 'Parameters[].[Name,Value]' --output text \
  | sed "s#^/ilro/$ENV_NAME/##; s#\t#=#" > "$ENV_FILE.tmp"
mv "$ENV_FILE.tmp" "$ENV_FILE"

# 2. 새 이미지 받기
aws ecr get-login-password --region "$REGION" \
  | docker login --username AWS --password-stdin "${IMAGE%%/*}"
docker pull "$IMAGE"

# 3. 지금 떠 있는 이미지 기억 (롤백용, 처음 배포면 비어 있음)
PREV_IMAGE=$(docker inspect --format '{{.Config.Image}}' "$NAME" 2>/dev/null || true)

start() {
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  docker run -d --name "$NAME" --restart unless-stopped -p "$PORT:8080" \
    --memory 768m -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60" \
    --env-file "$ENV_FILE" "$1" >/dev/null
}

healthy() {
  # 3초마다, 최대 90초
  for _ in $(seq 1 30); do
    curl -fsS "http://localhost:$PORT/actuator/health" >/dev/null 2>&1 && return 0
    sleep 3
  done
  return 1
}

# 4. 교체 → health 확인
echo "배포: $NAME ← $IMAGE (이전: ${PREV_IMAGE:-없음})"
if start "$IMAGE" && healthy; then
  echo "성공: $NAME 정상"
  # 안 쓰는 이미지 중 7일 지난 것 정리 (디스크 20GB)
  docker image prune -af --filter "until=168h" >/dev/null || true
  exit 0
fi

# 5. 실패 → 로그 남기고 직전 이미지로 되돌림
echo "실패: 90초 안에 health UP 안 됨. 마지막 로그:" >&2
docker logs --tail 50 "$NAME" >&2 || true
if [ -n "$PREV_IMAGE" ] && start "$PREV_IMAGE" && healthy; then
  echo "롤백 성공: $NAME ← $PREV_IMAGE" >&2
else
  echo "롤백 실패 또는 이전 이미지 없음: 서버 확인 필요" >&2
fi
exit 1