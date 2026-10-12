.PHONY: install build test test-backend test-frontend run-backend run-frontend clean deploy-check deploy-backend deploy-frontend db

install:
	cd backend-java && ./gradlew build -x test
	cd frontend && npm install && npx playwright install --with-deps chromium

test-backend:
	cd backend-java && ./gradlew clean test

test-frontend:
	cd frontend && npx playwright test

test: test-backend test-frontend

# Local dev boots the `dev` profile — seeded tenants, demo tools, and the
# /dev verification-token helper. prod runs with no demo surface at all.
run-backend:
	cd backend-java && SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun

run-frontend:
	cd frontend && npm run dev

build:
	cd backend-java && ./gradlew bootJar -x test
	cd frontend && npm run build

db:
	docker compose up -d postgres

deploy-check:
	cd backend-java && ./gradlew check
	cd frontend && npm run build

deploy-backend:
	./scripts/deploy-azure.sh

deploy-frontend:
	cd frontend && vercel --prod

clean:
	cd backend-java && ./gradlew clean
	rm -rf frontend/dist frontend/node_modules
