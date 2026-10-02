# rpg_sync_plugin (RpgSyncPlugin)

> **상위 프로젝트 연계**: 본 레포지토리는 [rpg_sync_project](https://github.com/mmmphyun/rpg_sync_project) (프리티어 단일 노드 환경에서의 DB I/O 최소화 및 Redis 분산 락/캐싱 기반 마인크래프트-디스코드 실시간 동기화 시스템)의 **마인크래프트 인게임 서버 전용 PaperMC 플러그인 모듈**입니다.

마인크래프트(PaperMC 1.20.1) 서버와 디스코드 음성 채널 간의 실시간 유저 상태 동기화 및 접근 제어를 담당하는 고성능 Spigot/Paper 서버 플러그인입니다.

---

## 1. 개요 (Overview)

RPG 서버 운영 환경에서 유저의 디스코드 음성 채널 접속 여부와 게임 내 활동 상태를 실시간으로 대조하고, 미인증 유저 및 음성 채널 이탈자를 정밀 제어하기 위해 설계되었습니다.
[rpg_sync_project](https://github.com/mmmphyun/rpg_sync_project)의 백엔드(FastAPI) 및 디스코드 봇과 유기적으로 통신하며, 메인 틱(Tick) 스레드 블로킹을 원천 차단하기 위해 **Redis Pub/Sub 비동기 이벤트 버스**, **HikariCP 커넥션 풀 기반 비동기 DB 파이프라인**, **로컬 메모리 L1 캐시**를 결합하여 설계되었습니다.

---

## 2. 통합 아키텍처 (Integrated Architecture)

```mermaid
flowchart TD
    subgraph Discord Ecosystem [Discord & Backend / rpg_sync_project]
        DiscordBot[Discord Bot & Voice State]
        BackendAPI[FastAPI Backend & Admin Web]
    end

    subgraph Minecraft Server [Minecraft Server / rpg_sync_plugin]
        PlayerJoin[PlayerJoinEvent / AsyncPreLogin]
        Command[/사유, /rpgsync]
        PAPI[PlaceholderAPI Hook]
        L1Cache[(ConcurrentHashMap L1 Cache)]
        KickScheduler[KickScheduler]
    end

    subgraph Shared Data Layer [Shared Infrastructure]
        RedisPubSub[Redis Pub/Sub Channel]
        RedisCache[(Redis Distributed Cache)]
        PostgresDB[(Supabase PostgreSQL)]
    end

    DiscordBot -->|Publish Voice Events| RedisPubSub
    BackendAPI -->|Manage Whitelist & Logs| PostgresDB
    BackendAPI -->|Admin Control| RedisPubSub

    PlayerJoin -->|Async Pre-warm| PostgresDB
    PlayerJoin -->|Read 0ms| L1Cache
    PAPI -->|Read 0ms| L1Cache
    Command -->|TTL / Rate-limit| RedisCache
    RedisPubSub -->|Event Consume| KickScheduler
    KickScheduler -->|Warning / Kick| PlayerJoin
```

---

## 3. 핵심 기술 및 최적화 포인트 (Key Highlights)

### 3.1. Zero Main-Thread Blocking (완전 비동기 I/O)
- **Async Pre-warming**: 유저 접속 단계(`AsyncPlayerPreLoginEvent`)에서 Supabase DB로부터 한국어 닉네임 및 권한 데이터를 비동기로 사전 로드합니다.
- **L1 In-Memory Cache**: 사전 로드된 유저 메타데이터는 `ConcurrentHashMap` 기반 로컬 캐시에 저장되며, 챗 렌더링 및 PlaceholderAPI(`%rpgsync_korean_name%`) 호출 시 DB 조회 지연 시간 **0ms**를 보장합니다.

### 3.2. Redis 기반 분산 캐시 & 이벤트 버스
- **실시간 Pub/Sub 동기화**: 디스코드 봇(Bot) 또는 외부 시스템에서 음성 채널 입/퇴장 이벤트를 Redis 채널로 발행(`Publish`)하면, 플러그인의 백그라운드 리스너 스레드가 즉각 수신(`Subscribe`)하여 게임 내 상태를 실시간 갱신합니다.
- **원자적 분산 락 및 TTL 제어**: 임시 예외 유저(`temp_bypass`) 및 명령어 쿨다운(`reason_cooldown`)을 Redis의 `SETEX` 원자적 연산으로 처리하여 서버 재시작 시에도 상태를 안전하게 유지합니다.
- **Fail-Safe & 서킷 브레이커 구조**: Redis 연결 유실 시 자동으로 연결 풀 복구를 시도하며, 장애 상황에서도 게임 서버 메인 틱이 정지되지 않도록 격리 처리되어 있습니다.

### 3.3. 안정적인 리소스 라이프사이클 관리
- **Graceful Shutdown**: 서버 정지(`onDisable`) 및 플러그인 리로드 시 실행 중인 비동기 타이머, Redis Pub/Sub 구독 스레드, HikariCP 커넥션 풀을 순차적으로 안전하게 해제하여 메모리 누수와 고스트 커넥션을 방지합니다.

---

## 4. 기술 스택 (Tech Stack)

- **Platform**: PaperMC (Java 17, Minecraft 1.20.1)
- **Build Tool**: Gradle 8.8 (Shadow Jar)
- **In-Memory Cache & Message Broker**: Redis (Jedis 5.1.0)
- **Database**: PostgreSQL / Supabase (HikariCP 5.1.0)
- **External Integration**: PlaceholderAPI 2.11.5

---

## 5. 명령어 및 권한 (Commands & Permissions)

| 명령어 | 권한 | 설명 |
| :--- | :--- | :--- |
| `/사유 <상세내용>` | 없음 (기본 유저) | 디스코드 음성 채널 일시 이탈 사유를 전송하고 쿨다운을 적용합니다. |
| `/rpgsync bypass <add/remove/check> <플레이어>` | `rpgsync.admin` | 특정 플레이어의 음성 채널 검증 예외 처리를 부여/해제/확인합니다. |
| `/rpgsync redis <status/reconnect>` | `rpgsync.admin` | Redis 연결 상태를 점검하거나 커넥션 풀 재연결을 수행합니다. |
| `/rpgsync reload` | `rpgsync.admin` | 플러그인 설정 및 DB/Redis 풀 전체를 안전하게 리로드합니다. |

---

## 6. 빌드 및 배포 (Build)

Shadow Jar 플러그인을 사용하여 외부 라이브러리(Jedis, HikariCP, PostgreSQL 드라이버)가 패키지 내부에 리로케이션되어 단일 실행 Jar로 패키징됩니다.

```bash
# Shadow Jar 빌드
./gradlew shadowJar

# 산출물 경로: build/libs/RpgSyncPlugin-1.0.jar
```
