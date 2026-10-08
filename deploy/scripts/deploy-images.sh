#!/usr/bin/env bash

set -Eeuo pipefail

app_dir="${TODAYIT_APP_DIR:-/opt/todayit}"
compose_dir="${app_dir}/compose"
compose_file="${compose_dir}/compose.yml"
env_file="${compose_dir}/.env"
lock_file="${app_dir}/.deploy.lock"
failed_image_file="${app_dir}/.failed-api-image"

compose=(docker compose --env-file "$env_file" -f "$compose_file")

log() {
  printf '[%s] %s\n' "$(date --iso-8601=seconds)" "$*"
}

rollback() {
  if [[ -n "${candidate_image_id:-}" ]]; then
    printf '%s\n' "$candidate_image_id" >"$failed_image_file"
  fi

  if [[ -z "${previous_image_id:-}" ]]; then
    log "이전 API 이미지가 없어 자동 롤백할 수 없습니다."
    return 1
  fi

  log "API 상태 확인 실패: 이전 이미지로 롤백합니다."
  docker image tag "$previous_image_id" "$api_image"
  "${compose[@]}" up -d --force-recreate --no-build --no-deps api
}

for required_file in "$compose_file" "$env_file"; do
  if [[ ! -f "$required_file" ]]; then
    log "필수 파일을 찾을 수 없습니다: $required_file"
    exit 1
  fi
done

exec 9>"$lock_file"
if ! flock -n 9; then
  log "다른 이미지 배포가 실행 중이므로 종료합니다."
  exit 0
fi

api_image="$(sed -n 's/^API_IMAGE=//p' "$env_file" | tail -n 1)"
if [[ -z "$api_image" || "$api_image" == *[[:space:]]* ]]; then
  log ".env의 API_IMAGE 값이 올바르지 않습니다."
  exit 1
fi

"${compose[@]}" config --quiet

# Pull이 가변 태그를 새 이미지로 옮기기 전에 실행·정지 상태를 포함한 기존 이미지를 보존한다.
previous_container="$("${compose[@]}" ps --all --quiet api)"
previous_image_id=""
if [[ -n "$previous_container" ]]; then
  previous_image_id="$(docker inspect --format '{{.Image}}' "$previous_container")"
else
  # 컨테이너가 삭제됐어도 로컬에 기존 이미지가 남아 있으면 롤백 대상으로 사용한다.
  previous_image_id="$(docker image inspect --format '{{.Id}}' "$api_image" 2>/dev/null || true)"
fi

log "새 API 이미지를 확인합니다: $api_image"
"${compose[@]}" pull api
candidate_image_id="$(docker image inspect --format '{{.Id}}' "$api_image")"

if [[ -f "$failed_image_file" ]] && [[ "$(<"$failed_image_file")" == "$candidate_image_id" ]]; then
  log "이 이미지는 이전 배포에서 실패했으므로 새 이미지가 게시될 때까지 건너뜁니다."
  exit 0
fi

if ! "${compose[@]}" up -d --no-build --no-deps api; then
  rollback
  exit 1
fi

api_container="$("${compose[@]}" ps -q api)"
if [[ -z "$api_container" ]]; then
  rollback
  exit 1
fi

for _ in $(seq 1 40); do
  health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$api_container")"

  if [[ "$health" == "healthy" ]]; then
    if curl --fail --silent --show-error --max-time 5 http://127.0.0.1/nginx-health >/dev/null; then
      rm -f "$failed_image_file"
      log "API 이미지 배포와 Nginx 상태 확인이 완료됐습니다."
      exit 0
    fi
  fi

  if [[ "$health" == "unhealthy" || "$health" == "exited" || "$health" == "dead" ]]; then
    break
  fi

  sleep 3
done

"${compose[@]}" logs --tail=100 api >&2 || true
rollback
exit 1
