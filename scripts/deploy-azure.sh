#!/usr/bin/env bash
# Deploys backend-java/ to Azure Container Apps (consumption plan).
#
# Encoded gotchas:
#  - ACA only runs linux/amd64 — Apple-Silicon builds need an explicit
#    cross-platform buildx build.
#  - ACA rejects OCI index manifests carrying attestation entries —
#    provenance/sbom must be disabled.
#  - Tag pulls can resolve a stale manifest on ACA — the app is updated
#    with the image's immutable @sha256 digest instead.
#  - Supabase's direct host is IPv6-only on the free tier — use the
#    Supavisor pooler endpoint.
#  - Student subs restrict deployable regions — swedencentral is allowed.
#
# Requires: az login, docker buildx, and the prod secrets exported in the
# environment (or in a gitignored .env.deploy). See .env.example.
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f .env.deploy ] && { set -a; . ./.env.deploy; set +a; }

RG=${RG:-wise-escrow-rg}
APP=${APP:-wise-escrow-api}
ACR=${ACR:-wiseescrowacr}
REGION=${REGION:-swedencentral}
ENV_NAME=${ENV_NAME:-wise-env}
IMAGE_REPO="$ACR.azurecr.io/wise-escrow-backend"
TAG=latest

: "${SPRING_DATASOURCE_URL:?set SPRING_DATASOURCE_URL (pooler endpoint)}"
: "${SPRING_DATASOURCE_USERNAME:?set SPRING_DATASOURCE_USERNAME (postgres.<ref>)}"
: "${SPRING_DATASOURCE_PASSWORD:?set SPRING_DATASOURCE_PASSWORD}"
: "${JWT_SECRET:?set JWT_SECRET}"
: "${WISE_FIELD_ENCRYPTION_KEY:?set WISE_FIELD_ENCRYPTION_KEY}"
: "${WISE_GITHUB_WEBHOOK_SECRET:?set WISE_GITHUB_WEBHOOK_SECRET}"
: "${WISE_LOGISTICS_WEBHOOK_SECRET:?set WISE_LOGISTICS_WEBHOOK_SECRET}"
WISE_CORS_ORIGINS=${WISE_CORS_ORIGINS:-https://wise-escrow.vercel.app}

echo ">> building linux/amd64 image (no attestations) and pushing to $IMAGE_REPO:$TAG"
docker buildx build --platform linux/amd64 --provenance=false --sbom=false \
  -t "$IMAGE_REPO:$TAG" --push backend-java >/dev/null

DIGEST=$(az acr repository show -n "$ACR" --image "wise-escrow-backend:$TAG" \
  --query digest -o tsv)
echo ">> pushed digest $DIGEST"

if az containerapp show -g "$RG" -n "$APP" >/dev/null 2>&1; then
  echo ">> updating $APP (digest-pinned, scale-to-zero)"
  az containerapp update -g "$RG" -n "$APP" \
    --image "$IMAGE_REPO@$DIGEST" --min-replicas 0 --output none
else
  echo ">> creating resource group / env / app"
  az group create -n "$RG" -l "$REGION" --output none
  az containerapp env create -g "$RG" -n "$ENV_NAME" -l "$REGION" --output none
  az acr create -g "$RG" -n "$ACR" --sku Basic --admin-enabled true \
    -l "$REGION" --output none 2>/dev/null || true
  az containerapp create -g "$RG" -n "$APP" --environment "$ENV_NAME" \
    --image "$IMAGE_REPO@$DIGEST" \
    --registry-server "$ACR.azurecr.io" --registry-username "$ACR" \
    --registry-password "$(az acr credential show -n "$ACR" --query 'passwords[0].value' -o tsv)" \
    --target-port 8080 --ingress external \
    --min-replicas 0 --max-replicas 1 --cpu 0.5 --memory 1Gi \
    --secrets \
      "dbpw=$SPRING_DATASOURCE_PASSWORD" "jwt=$JWT_SECRET" \
      "enckey=$WISE_FIELD_ENCRYPTION_KEY" "ghsec=$WISE_GITHUB_WEBHOOK_SECRET" \
      "losec=$WISE_LOGISTICS_WEBHOOK_SECRET" \
    --env-vars \
      "SPRING_PROFILES_ACTIVE=prod" \
      "SPRING_DATASOURCE_URL=$SPRING_DATASOURCE_URL" \
      "SPRING_DATASOURCE_USERNAME=$SPRING_DATASOURCE_USERNAME" \
      "SPRING_DATASOURCE_PASSWORD=secretref:dbpw" \
      "JWT_SECRET=secretref:jwt" \
      "WISE_FIELD_ENCRYPTION_KEY=secretref:enckey" \
      "WISE_GITHUB_WEBHOOK_SECRET=secretref:ghsec" \
      "WISE_LOGISTICS_WEBHOOK_SECRET=secretref:losec" \
      "WISE_CORS_ORIGINS=$WISE_CORS_ORIGINS" \
      "PORT=8080" \
    --output none
fi

FQDN=$(az containerapp show -g "$RG" -n "$APP" \
  --query "properties.configuration.ingress.fqdn" -o tsv)
echo ">> live at https://$FQDN (health: /actuator/health)"
