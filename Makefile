.PHONY: install build test test-e2e run-backend run-frontend clean deploy-check db

install:
	cd backend-java && ./gradlew build -x test
	cd frontend && npm install && npx playwright install --with-deps chromium

db:
	docker compose up -d postgres

run-backend:
	cd backend-java && ./gradlew bootRun

run-frontend:
	cd frontend && npm run dev

build:
	cd backend-java && ./gradlew bootJar
	cd frontend && npm run build

test:
	cd backend-java && ./gradlew clean test
	cd frontend && npx playwright test

test-e2e:
	cd frontend && npx playwright test

deploy-check:
	cd backend-java && ./gradlew check
	cd frontend && npm run build

clean:
	cd backend-java && ./gradlew clean
	rm -rf frontend/dist
