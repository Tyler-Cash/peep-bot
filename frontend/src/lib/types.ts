// Backend DTOs — generated from openapi.json. Edits to these types must be made
// on the backend Java DTOs; run `cd backend && ./gradlew generateOpenApiDocs`
// then `cd frontend && npm run codegen` to regenerate.
//
// NOTE: The generated DTO fields are mostly optional because the OpenAPI spec
// doesn't annotate required fields. Consumers that need stronger guarantees
// should validate at the call-site (e.g. using the Zod schemas in
// src/lib/api/generated/zod.gen.ts) rather than tightening these types here.
export type {
  AttendeeDto,
  AttendeeStatDto,
  EventCategoryDto,
  EventDetailDto,
  EventDto,
  EventSummaryDto,
  EventUpdateDto,
  GalleryAlbumDto,
  GraphEdgeDto,
  GraphNodeDto,
  GuildDto as Guild,
  GuildSettingsDto,
  RewindStatsDto as RewindStats,
  SocialGraphDto,
  UserInfoDto as UserInfo,
} from "./api/generated";

// --- Frontend-only types ---

// Category is a frontend-side string union used in mock fixtures and UI
// components. It overlaps with EventDto.category (string) on the backend but
// is kept separate because the backend doesn't restrict category values via
// the OpenAPI spec.
export type Category =
  | "movie"
  | "trivia"
  | "show"
  | "food"
  | "outdoor"
  | "game"
  | "social"
  | "market"
  | "trip"
  | "other";

// Attendee is frontend-shaped — includes UI-only fields (hue) and treats
// several fields as required that the backend AttendeeDto marks optional.
// Kept separate intentionally; document overlap with a comment.
// Backend equivalent: AttendeeDto (from ./api/generated).
export type Attendee = {
  snowflake: string | null;
  name: string;
  username?: string | null;
  instant: string; // ISO
  ownerSnowflake?: string | null;
  avatarUrl?: string | null;
  hue?: string; // fallback accent colour when no avatar
};

export type RsvpStatus = "going" | "maybe" | "declined";

export type BackendError = { status: number; message: string };
