import { createFileRoute } from "@tanstack/react-router";
import { VideoMoverReview } from "@/components/video-mover-review";

export const Route = createFileRoute("/")({ component: VideoMoverReview });
