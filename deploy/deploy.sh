#!/usr/bin/env bash
# EC2에서 실행 (GitHub Actions → SSM, root 권한)
# 새 이미지로 컨테이너를 바꾸고, health가 90초 안에 UP이 안 되면 직전 이미지·설정으로 되돌림
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

# 환경과 프로필이 다르면(예: prod에 dev 프로필) 컨테이너를 건드리기 전에 중단
if ! grep -qx "SPRING_PROFILES_ACTIVE=$ENV_NAME" "$ENV_FILE.tmp"; then
  echo "Parameter Store /ilro/$ENV_NAME/SPRING_PROFILES_ACTIVE 값이 $ENV_NAME이 아님" >&2
  exit 1
fi

# 롤백용으로 지금 설정을 남겨 둠 (재부팅 뒤 첫 배포라 없으면 새 설정을 그대로 씀)
if [ -f "$ENV_FILE" ]; then cp "$ENV_FILE" "$ENV_FILE.prev"; else cp "$ENV_FILE.tmp" "$ENV_FILE.prev"; fi
mv "$ENV_FILE.tmp" "$ENV_FILE"

# 2. 새 이미지 받기
aws ecr get-login-password --region "$REGION" \
  | docker login --username AWS --password-stdin "${IMAGE%%/*}"
docker pull "$IMAGE"

# 3. 지금 떠 있는 이미지 기억 (롤백용, 처음 배포면 비어 있음)
PREV_IMAGE=$(docker inspect --format '{{.Config.Image}}' "$NAME" 2>/dev/null || true)

# $1 이미지, $2 env 파일
start() {
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  # 로그는 CloudWatch Logs /ilro/<환경>/app으로 (그룹·보존 기간은 콘솔에서 미리 생성)
  # non-blocking: CloudWatch가 느려도 서버 응답은 안 막힘 (버퍼가 넘치면 그 로그는 버림)
  docker run -d --name "$NAME" --restart unless-stopped -p "$PORT:8080" \
    --memory 768m -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60" \
    --log-driver awslogs \
    --log-opt awslogs-region="$REGION" \
    --log-opt awslogs-group="/ilro/$ENV_NAME/app" \
    --log-opt mode=non-blocking --log-opt max-buffer-size=4m \
    --env-file "$2" "$1" >/dev/null
}

# 3초마다, 요청 하나는 최대 5초, 전체 90초
healthy() {
  local deadline=$((SECONDS + 90))
  while (( SECONDS < deadline )); do
    curl -fsS --max-time 5 "http://localhost:$PORT/actuator/health" >/dev/null 2>&1 && return 0
    sleep 3
  done
  return 1
}

# 4. 교체 → health 확인
echo "배포: $NAME ← $IMAGE (이전: ${PREV_IMAGE:-없음})"
if start "$IMAGE" "$ENV_FILE" && healthy; then
  echo "성공: $NAME 정상"
  # 안 쓰는 이미지 중 7일 지난 것 정리 (디스크 20GB)
  docker image prune -af --filter "until=168h" >/dev/null || true
  exit 0
fi

# 5. 실패 → 로그 남기고 직전 이미지·설정으로 되돌림
echo "실패: 90초 안에 health UP 안 됨. 마지막 로그:" >&2
docker logs --tail 50 "$NAME" >&2 || true
if [ -n "$PREV_IMAGE" ] && start "$PREV_IMAGE" "$ENV_FILE.prev" && healthy; then
  cp "$ENV_FILE.prev" "$ENV_FILE"  # ★ 설정도 되돌린 상태로 맞춤
  echo "롤백 성공: $NAME ← $PREV_IMAGE" >&2
else
  echo "롤백 실패 또는 이전 이미지 없음: 서버 확인 필요" >&2
fi
exit 1