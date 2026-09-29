-- AUTO-GENERATED SCHEMA DUMP
-- Date: 2026-09-29T10:30:20.937Z

-- ========================
-- TABLES & COLUMNS
-- ========================
Table: api_keys
created_at (timestamp with time zone), is_active (boolean), id (bigint), last_used_at (timestamp with time zone), name (text), cooldown_until (timestamp with time zone), service (text), api_key (text)

Table: asset_upload_queue
zip_name (text), updated_at (timestamp with time zone), file_path (text), created_at (timestamp with time zone), id (bigint), error_message (text), status (text), book_name (text), image_name (text)

Table: book_flashcard_progress
chapter_title (text), book_id (uuid), id (uuid), created_at (timestamp with time zone), updated_at (timestamp with time zone), locked_until (timestamp with time zone), error_message (text), status (text), section_title (text), book_title (text), course_code (text), cards_generated (integer), end_page (integer), start_page (integer)

Table: book_pages
id (uuid), page_key (text), page_number (integer), created_at (timestamp with time zone), book_id (uuid), content_json (jsonb), manual_flag (text)

Table: book_progress
error_message (text), page_number (integer), updated_at (timestamp with time zone), created_at (timestamp with time zone), id (uuid), status (text), pdf_name (text)

Table: book_question_links
created_at (timestamp with time zone), similarity_score (double precision), question_id (uuid), chunk_id (uuid), id (uuid)

Table: book_results
result_json (jsonb), pdf_name (text), id (uuid), page_number (integer), created_at (timestamp with time zone)

Table: books
toc (jsonb), title (text), category (text), cover_url (text), author (text), course_code (text), custom_css (text), page_offset (integer), id (uuid), created_at (timestamp with time zone)

Table: campus_channels
is_active (boolean), id (uuid), created_at (timestamp with time zone), telegram_peer_id (bigint), is_private (boolean), members_data (jsonb), last_extracted_at (timestamp with time zone), last_scraped_id (bigint), channel_handle (text)

Table: campus_feed
channel_handle (text), sender_name (text), sender_username (text), image_url (text), full_text (text), telegram_timestamp (timestamp with time zone), sender_id (bigint), id (uuid), telegram_id (bigint), metadata (jsonb), created_at (timestamp with time zone)

Table: chunks
toc_node_id (uuid), prev_chunk_id (uuid), next_chunk_id (uuid), chunk_index (integer), chunk_text (text), id (uuid), document_id (uuid), page_number (integer), created_at (timestamp with time zone), embedding (USER-DEFINED)

Table: conduit_favorites
id (uuid), repo_name (text), target_id (text), category (text), created_at (timestamp with time zone), metadata (jsonb)

Table: conduit_history
conduit_id (integer), ops (jsonb), created_at (timestamp with time zone), repo_name (text), id (uuid), sha (text), title (text), type (text), meta (text), note (text)

Table: conduit_logs
created_at (timestamp with time zone), id (uuid), data (jsonb), repo_name (text), type (text)

Table: conversation_members
last_read_at (timestamp with time zone), id (uuid), conversation_id (uuid), user_id (uuid), role (USER-DEFINED), muted_until (timestamp with time zone), created_at (timestamp with time zone)

Table: conversations
id (uuid), avatar_url (text), title (character varying), metadata (jsonb), owner_id (uuid), last_message_at (timestamp with time zone), created_at (timestamp with time zone), type (USER-DEFINED)

Table: course_flashcards
front (text), section_title (text), chapter_title (text), back (text), book_id (uuid), id (uuid), course_code (text), ref_page (integer), created_at (timestamp with time zone)

Table: course_visual_notebooks
id (uuid), metadata (jsonb), created_at (timestamp with time zone), language (text), topic_title (text), scoped_css (text), course_code (text), html_body (text), page_number (integer)

Table: courses
code (text), department_id (uuid), created_at (timestamp with time zone), id (uuid), name (text)

Table: departments
created_at (timestamp with time zone), name (text), id (uuid)

Table: documents
created_at (timestamp with time zone), file_name (text), page_count (integer), last_processed_at (timestamp with time zone), chunk_count (integer), status (text), user_id (uuid), id (uuid), storage_path (text)

Table: embedding_progress
locked_until (timestamp with time zone), updated_at (timestamp with time zone), error_message (text), book_id (uuid), block_index (integer), status (text), id (uuid), page_number (integer)

Table: exams
id (uuid), course_id (uuid), created_at (timestamp with time zone), media_summary (jsonb), exam_quality_notes (jsonb), exam_type (text), date (text), constants_provided (jsonb), program (text), general_instructions (text), university_id (uuid), total_marks (numeric), time_allowed_minutes (integer)

Table: extracted_events
title (text), id (uuid), source_ids (ARRAY), created_at (timestamp with time zone), is_active (boolean), event_date (timestamp with time zone), event_type (text), channel_id (uuid), description (text)

Table: featured_events
weight (integer), external_url (text), html_content (text), action_type (text), button_color (text), button_text (text), tag_color (text), tag_text (text), image_url (text), body (text), title (text), id (uuid), app_route (jsonb), is_active (boolean), created_at (timestamp with time zone), metadata (jsonb)

Table: linkoin_transactions
amount (integer), idempotency_key (text), created_at (timestamp with time zone), transaction_type (text), id (uuid), user_id (uuid), description (text)

Table: live_stage_questions
conversation_id (uuid), is_pinned (boolean), created_at (timestamp with time zone), id (uuid), sender_id (uuid), status (text), text (text)

Table: live_study_sessions
conversation_id (uuid), compiled_answers (jsonb), layout_blueprint (jsonb), id (uuid), generation_state (text), raw_source_text (text), active_user_ids (ARRAY), lesson_topic (text), course_name (text), last_updated_at (timestamp with time zone), lecture_chunks (jsonb)

Table: messages
is_edited (boolean), reply_to_id (uuid), attachments (jsonb), sender_id (uuid), conversation_id (uuid), id (uuid), forward_meta (jsonb), text (text), created_at (timestamp with time zone)

Table: migration_progress
processed_at (timestamp with time zone), remote_id (text), id (uuid), pdf_name (text), page_index (text), status (text), error_message (text)

Table: migration_sync_state
current_offset (integer), last_run_at (timestamp with time zone), id (integer)

Table: miron_messages
ui_command (jsonb), created_at (timestamp with time zone), text (text), role (text), flashcards (jsonb), thought_process (text), id (uuid), thread_id (uuid), user_id (uuid), snapshots (jsonb), quizzes (jsonb)

Table: miron_threads
course_code (text), user_id (uuid), is_pinned (boolean), last_message_at (timestamp with time zone), created_at (timestamp with time zone), updated_at (timestamp with time zone), title (text), context_passage (text), id (uuid)

Table: news_feed
is_ad (boolean), id (bigint), telegram_id (bigint), telegram_timestamp (timestamp with time zone), created_at (timestamp with time zone), channel (text), title (text), snippet (text), full_text (text), image_url (text), post_url (text), category (text)

Table: notifications
icon (text), insight (text), id (uuid), title (text), created_at (timestamp with time zone), is_read (boolean), action_data (jsonb), description (text), user_id (uuid), type (text)

Table: payment_submissions
status (text), screenshot_url (text), sms_text (text), created_at (timestamp with time zone), updated_at (timestamp with time zone), transaction_ref (text), id (uuid), user_id (uuid), amount (integer), plan (text), payment_method (text), rejection_reason (text)

Table: peer_questions
id (uuid), body (text), title (text), replies_count (integer), course_tag (text), user_id (uuid), created_at (timestamp with time zone)

Table: poll_votes
option_index (integer), user_id (uuid), created_at (timestamp with time zone), message_id (uuid), id (uuid)

Table: profiles
registered_with_telegram (boolean), updated_at (timestamp with time zone), telegram_id (bigint), university_id (uuid), longest_streak (integer), class_id (uuid), last_seen_at (timestamp with time zone), linkoin_balance (integer), telegram_username (text), theme (text), bio (text), phone (text), program (text), target_department (text), year (text), is_pro (boolean), pro_expires_at (timestamp with time zone), freshman_stream (text), department (text), show_on_leaderboard (boolean), last_username_change_at (timestamp with time zone), username (text), level (text), id (uuid), avatar_url (text), full_name (text), last_streak_update (date), current_streak (integer)

Table: question_book_mappings
book_id (uuid), id (uuid), question_id (uuid), is_valid (boolean), content_index (integer), processed_at (timestamp with time zone), created_at (timestamp with time zone), error_message (text), snippet (text), page_key (text), status (text)

Table: question_processing_progress
book_id (uuid), question_id (uuid), error_message (text), created_at (timestamp with time zone), status (text), processed_at (timestamp with time zone)

Table: question_reports
source (text), created_at (timestamp with time zone), id (uuid), question_id (uuid), status (text), report_text (text)

Table: questions
points (numeric), question_order (integer), embedding_status (text), section_id (uuid), id (uuid), created_at (timestamp with time zone), retry_count (integer), embedding (USER-DEFINED), correct_answer (jsonb), text (text), question_type (text), explanation (text), options (jsonb), matching_data (jsonb), media (jsonb), transcription_quality (jsonb), question_number (text)

Table: referrals
status (text), referee_id (uuid), id (uuid), referrer_id (uuid), created_at (timestamp with time zone)

Table: sections
exam_id (uuid), id (uuid), title (text), instructions (text), created_at (timestamp with time zone), section_order (integer), shared_context (jsonb), total_points (numeric)

Table: squad_bans
created_at (timestamp with time zone), id (uuid), banned_until (timestamp with time zone), conversation_id (uuid), user_id (uuid)

Table: system_config
value (jsonb), key (text)

Table: telegram_login_tokens
expires_at (timestamp with time zone), created_at (timestamp with time zone), id (uuid), token_hash (text), telegram_id (bigint), metadata (jsonb)

Table: universities
id (uuid), created_at (timestamp with time zone), short_name (text), name (text)

Table: user_course_progress
reading_seconds (integer), updated_at (timestamp with time zone), created_at (timestamp with time zone), last_read_at (timestamp with time zone), id (uuid), user_id (uuid), book_id (uuid), current_page (integer), section_title (text), chapter_title (text), course_code (text), completion_pct (numeric), furthest_page (integer)

Table: user_custom_flashcards
ref (text), created_at (timestamp with time zone), back (text), front (text), chapter_title (text), course_code (text), user_id (uuid), id (uuid), is_mistake (boolean)

Table: user_daily_telemetry
id (uuid), user_id (uuid), date (date), total_active_seconds (integer), time_miron_seconds (integer), time_exam_seconds (integer), time_discover_seconds (integer), time_books_seconds (integer), updated_at (timestamp with time zone), time_home_seconds (integer), time_connect_seconds (integer), total_interactions (integer)

Table: user_device_seats
id (uuid), device_name (text), device_type (text), device_id (text), last_active_at (timestamp with time zone), last_lease_at (timestamp with time zone), created_at (timestamp with time zone), is_primary (boolean), user_id (uuid)

Table: user_flashcard_reviews
difficulty (text), last_reviewed_at (timestamp with time zone), id (uuid), user_id (uuid), card_id (uuid), review_count (integer), card_type (text), next_review_at (timestamp with time zone)

Table: user_flashcard_srs
ease_factor (numeric), repetitions (integer), lapses (integer), user_id (uuid), card_id (uuid), state (text), card_type (text), interval_days (numeric), next_review_at (timestamp with time zone), last_reviewed_at (timestamp with time zone)

Table: user_mistake_flashcards
back (text), id (uuid), front (text), course_code (text), reference_info (text), user_id (uuid), created_at (timestamp with time zone), source_question_id (uuid)

Table: user_question_attempts
id (uuid), user_id (uuid), source_type (text), topic_tag (text), question_snapshot (jsonb), user_answer (jsonb), is_correct (boolean), attempted_at (timestamp with time zone), question_id (uuid), course_code (text), source_id (text)

-- ========================
-- RLS POLICIES
-- ========================
Table: profiles | Policy: Allow update for owners | Cmd: UPDATE | Using: (auth.uid() = id)
Table: conversations | Policy: Conversations visibility | Cmd: SELECT | Using: is_member_of(id)
Table: conversation_members | Policy: Members visibility | Cmd: SELECT | Using: (is_member_of(conversation_id) OR (user_id = auth.uid()))
Table: messages | Policy: Messages visibility | Cmd: SELECT | Using: is_member_of(conversation_id)
null
Table: messages | Policy: Users can update their own messages | Cmd: UPDATE | Using: (auth.uid() = sender_id)
Table: messages | Policy: Users can delete their own messages | Cmd: DELETE | Using: (auth.uid() = sender_id)
Table: user_question_attempts | Policy: Users can read their own attempts | Cmd: SELECT | Using: (auth.uid() = user_id)
Table: news_feed | Policy: Allow public read access | Cmd: SELECT | Using: true
Table: conversations | Policy: Owners can update their squads | Cmd: UPDATE | Using: (auth.uid() = owner_id)
Table: conversations | Policy: Owners can delete their squads | Cmd: DELETE | Using: (auth.uid() = owner_id)
Table: messages | Policy: Dynamic Read Access for Messages | Cmd: SELECT | Using: ((EXISTS ( SELECT 1
   FROM conversation_members
  WHERE ((conversation_members.conversation_id = messages.conversation_id) AND (conversation_members.user_id = auth.uid())))) OR (EXISTS ( SELECT 1
   FROM conversations
  WHERE ((conversations.id = messages.conversation_id) AND ((conversations.metadata ->> 'privacy'::text) = 'public'::text)))))
Table: conversations | Policy: Dynamic Read Access for Conversations | Cmd: SELECT | Using: (((metadata ->> 'privacy'::text) = 'public'::text) OR (auth.uid() = owner_id) OR (EXISTS ( SELECT 1
   FROM conversation_members
  WHERE ((conversation_members.conversation_id = conversations.id) AND (conversation_members.user_id = auth.uid())))))
Table: books | Policy: Auth read only | Cmd: SELECT | Using: (auth.role() = 'authenticated'::text)
Table: book_pages | Policy: Auth read only | Cmd: SELECT | Using: (auth.role() = 'authenticated'::text)
Table: exams | Policy: Auth read only | Cmd: SELECT | Using: (auth.role() = 'authenticated'::text)
Table: questions | Policy: Auth read only | Cmd: SELECT | Using: (auth.role() = 'authenticated'::text)
Table: sections | Policy: Auth read only | Cmd: SELECT | Using: (auth.role() = 'authenticated'::text)
Table: courses | Policy: Auth read only | Cmd: SELECT | Using: (auth.role() = 'authenticated'::text)
null
null
Table: profiles | Policy: Sensitive data visibility | Cmd: SELECT | Using: (auth.uid() = id)
Table: profiles | Policy: Users can delete their own profile | Cmd: DELETE | Using: (auth.uid() = id)
Table: conversation_members | Policy: Users can update their own conversation member status | Cmd: UPDATE | Using: (auth.uid() = user_id)
null
Table: peer_questions | Policy: Public read peer_questions | Cmd: SELECT | Using: true
null
Table: notifications | Policy: Users can read own notifications | Cmd: SELECT | Using: (auth.uid() = user_id)
Table: notifications | Policy: Users can update own notifications | Cmd: UPDATE | Using: (auth.uid() = user_id)
Table: featured_events | Policy: Public read featured_events | Cmd: SELECT | Using: (is_active = true)
Table: live_study_sessions | Policy: Public read active sessions | Cmd: SELECT | Using: true
null
null
null
Table: live_stage_questions | Policy: Hostess can update live questions | Cmd: UPDATE | Using: (auth.uid() = ( SELECT ((conversations.metadata ->> 'live_host_id'::text))::uuid AS uuid
   FROM conversations
  WHERE (conversations.id = live_stage_questions.conversation_id)))
null
Table: user_daily_telemetry | Policy: Users can update their own telemetry | Cmd: UPDATE | Using: (auth.uid() = user_id)
Table: user_daily_telemetry | Policy: Users can read their own telemetry | Cmd: SELECT | Using: (auth.uid() = user_id)
Table: live_stage_questions | Policy: Hostess can delete live questions | Cmd: DELETE | Using: (auth.uid() = ( SELECT ((conversations.metadata ->> 'live_host_id'::text))::uuid AS uuid
   FROM conversations
  WHERE (conversations.id = live_stage_questions.conversation_id)))
Table: messages | Policy: Admins and Owners can delete any group messages | Cmd: DELETE | Using: (EXISTS ( SELECT 1
   FROM conversation_members cm
  WHERE ((cm.conversation_id = messages.conversation_id) AND (cm.user_id = auth.uid()) AND (cm.role = ANY (ARRAY['owner'::member_role, 'admin'::member_role])))))
Table: live_study_sessions | Policy: Hosts can manage live sessions | Cmd: ALL | Using: (EXISTS ( SELECT 1
   FROM conversation_members cm
  WHERE ((cm.conversation_id = live_study_sessions.conversation_id) AND (cm.user_id = auth.uid()) AND (cm.role = ANY (ARRAY['owner'::member_role, 'admin'::member_role])))))
Table: live_stage_questions | Policy: Members can read live questions | Cmd: SELECT | Using: is_member_of(conversation_id)
Table: poll_votes | Policy: Public read for poll votes | Cmd: SELECT | Using: true
Table: linkoin_transactions | Policy: Users can view their own transactions | Cmd: SELECT | Using: (auth.uid() = user_id)
Table: referrals | Policy: Users can view their own referrals | Cmd: SELECT | Using: ((auth.uid() = referrer_id) OR (auth.uid() = referee_id))
null
Table: user_course_progress | Policy: Users can read own course progress | Cmd: SELECT | Using: (auth.uid() = user_id)
Table: user_course_progress | Policy: Users can insert/update own course progress | Cmd: ALL | Using: (auth.uid() = user_id)
Table: miron_threads | Policy: Users can manage their own miron threads | Cmd: ALL | Using: (auth.uid() = user_id)
Table: miron_messages | Policy: Users can manage their own miron messages | Cmd: ALL | Using: (auth.uid() = user_id)
Table: payment_submissions | Policy: Users can view their own payment submissions | Cmd: SELECT | Using: (auth.uid() = user_id)
null
Table: payment_submissions | Policy: Users can update their pending payment submissions | Cmd: UPDATE | Using: ((auth.uid() = user_id) AND (status = 'pending'::text))
Table: user_device_seats | Policy: Users can manage own device seats | Cmd: ALL | Using: (auth.uid() = user_id)
Table: course_flashcards | Policy: Allow authenticated read for course_flashcards | Cmd: SELECT | Using: (auth.role() = 'authenticated'::text)
Table: user_mistake_flashcards | Policy: Users can manage their own mistake flashcards | Cmd: ALL | Using: (auth.uid() = user_id)
Table: user_flashcard_reviews | Policy: Users can manage their own flashcard reviews | Cmd: ALL | Using: (auth.uid() = user_id)
Table: book_flashcard_progress | Policy: Allow authenticated read for bfp | Cmd: SELECT | Using: (auth.role() = 'authenticated'::text)
Table: user_flashcard_srs | Policy: Users manage their own flashcard SRS | Cmd: ALL | Using: (auth.uid() = user_id)
Table: user_custom_flashcards | Policy: Users own their custom flashcards | Cmd: ALL | Using: (auth.uid() = user_id)
Table: course_visual_notebooks | Policy: Allow public read access to visual course notebooks | Cmd: SELECT | Using: true

-- ========================
-- FUNCTIONS & RPCs
-- ========================
-- Function: get_pending_mistake_batch

BEGIN
    RETURN QUERY
    SELECT 
        uqa.id AS attempt_id,
        uqa.question_id,
        COALESCE(uqa.course_code, 'General') AS course_code,
        COALESCE(uqa.topic_tag, 'Core Concept') AS topic_tag,
        COALESCE(uqa.question_snapshot->>'text', '') AS question_text,
        COALESCE(uqa.question_snapshot->>'type', 'multiple_choice') AS question_type,
        COALESCE(uqa.question_snapshot->'options', '[]'::jsonb) AS options,
        COALESCE(uqa.question_snapshot->'correct_answer', 'null'::jsonb) AS correct_answer,
        uqa.user_answer
    FROM public.user_question_attempts uqa
    WHERE uqa.user_id = p_user_id
      AND uqa.is_correct = false
      AND NOT EXISTS (
          SELECT 1 FROM public.user_mistake_flashcards umf
          WHERE umf.user_id = p_user_id 
            AND (umf.source_question_id = uqa.question_id OR umf.front ILIKE '%' || LEFT(uqa.question_snapshot->>'text', 40) || '%')
      )
    ORDER BY uqa.attempted_at DESC
    LIMIT p_limit;
END;


-- Function: get_next_pending_book_section

DECLARE
    v_book RECORD;
    v_target_sec RECORD;
    v_offset INT;
BEGIN
    FOR v_book IN 
        SELECT b.id, b.course_code, b.title, b.toc, COALESCE(b.page_offset, 0) AS page_offset 
        FROM public.books b 
        WHERE b.toc IS NOT NULL AND jsonb_array_length(b.toc) > 0
        ORDER BY b.created_at ASC
    LOOP
        v_offset := v_book.page_offset;
        
        -- Build an ordered sequence of sections with lookahead to find true section boundaries
        FOR v_target_sec IN
            WITH flattened_toc AS (
                SELECT 
                    ch->>'title' AS ch_title,
                    sec->>'title' AS sec_title,
                    (sec->>'page')::int AS raw_page,
                    LEAD((sec->>'page')::int) OVER () AS next_raw_page
                FROM jsonb_array_elements(v_book.toc) AS ch,
                LATERAL jsonb_array_elements(
                    CASE 
                        WHEN ch->'children' IS NOT NULL AND jsonb_array_length(ch->'children') > 0 
                        THEN ch->'children' 
                        ELSE jsonb_build_array(ch) 
                    END
                ) AS sec
            )
            SELECT 
                ch_title,
                sec_title,
                raw_page,
                next_raw_page
            FROM flattened_toc
        LOOP
            -- Check if this section has already been processed in course_flashcards
            IF NOT EXISTS (
                SELECT 1 FROM public.course_flashcards cf
                WHERE cf.book_id = v_book.id AND cf.section_title = v_target_sec.sec_title
            ) THEN
                book_id := v_book.id;
                course_code := v_book.course_code;
                book_title := v_book.title;
                chapter_title := v_target_sec.ch_title;
                section_title := v_target_sec.sec_title;
                start_page := GREATEST(1, v_target_sec.raw_page + v_offset);
                
                -- The True Boundary: Reads up to the exact page before the next section begins
                IF v_target_sec.next_raw_page IS NOT NULL AND v_target_sec.next_raw_page > v_target_sec.raw_page THEN
                    end_page := GREATEST(start_page, (v_target_sec.next_raw_page + v_offset) - 1);
                ELSE
                    -- If final section of the book, allow full span through the chapter end
                    end_page := start_page + 25;
                END IF;

                RETURN NEXT;
                RETURN; -- Terminate immediately to fulfill "one section per ping"
            END IF;
        END LOOP;
    END LOOP;
END;


-- Function: complete_flashcard_job

BEGIN
    UPDATE public.book_flashcard_progress
    SET status = p_status,
        cards_generated = p_cards_count,
        error_message = p_error,
        locked_until = NULL,
        updated_at = now()
    WHERE id = p_job_id;
END;


-- Function: initialize_book_flashcard_jobs

DECLARE
    v_inserted_count INT := 0;
BEGIN
    WITH raw_chapters AS (
        SELECT 
            b.id AS book_id,
            b.course_code,
            b.title AS book_title,
            COALESCE(b.page_offset, 0) AS page_offset,
            ch.elem->>'title' AS chapter_title,
            CASE 
                WHEN (ch.elem->>'page') ~ '^\d+$' THEN (ch.elem->>'page')::int 
                ELSE 1 
            END AS raw_start_page,
            ch.idx AS chapter_order
        FROM public.books b
        CROSS JOIN LATERAL jsonb_array_elements(b.toc) WITH ORDINALITY ch(elem, idx)
        WHERE b.toc IS NOT NULL AND jsonb_typeof(b.toc) = 'array'
    ),
    book_max_pages AS (
        SELECT 
            bp.book_id,
            MAX(bp.page_number) AS max_page
        FROM public.book_pages bp
        GROUP BY bp.book_id
    ),
    positioned_chapters AS (
        SELECT 
            rc.book_id,
            rc.course_code,
            rc.book_title,
            rc.chapter_title,
            rc.chapter_title AS section_title,
            GREATEST(1, rc.raw_start_page + rc.page_offset) AS start_page,
            LEAD(GREATEST(1, rc.raw_start_page + rc.page_offset)) OVER (
                PARTITION BY rc.book_id 
                ORDER BY rc.chapter_order ASC
            ) AS next_chapter_start,
            COALESCE(bmp.max_page, GREATEST(1, rc.raw_start_page + rc.page_offset) + 50) AS book_max_page
        FROM raw_chapters rc
        LEFT JOIN book_max_pages bmp ON bmp.book_id = rc.book_id
    ),
    filtered_real_chapters AS (
        SELECT 
            pc.book_id,
            pc.course_code,
            pc.book_title,
            pc.chapter_title,
            pc.section_title,
            pc.start_page,
            CASE 
                WHEN pc.next_chapter_start IS NOT NULL AND pc.next_chapter_start > pc.start_page
                THEN pc.next_chapter_start - 1
                WHEN pc.next_chapter_start IS NOT NULL AND pc.next_chapter_start <= pc.start_page
                THEN pc.start_page
                ELSE pc.book_max_page
            END AS end_page
        FROM positioned_chapters pc
        WHERE 
            -- STRICT FILTER: Only real chapters or units (kills Preface, Acknowledgments, References)
            pc.chapter_title ~* '^(chapter|unit)[-\s:]'
    )
    INSERT INTO public.book_flashcard_progress (
        book_id,
        course_code,
        book_title,
        chapter_title,
        section_title,
        start_page,
        end_page
    )
    SELECT 
        fc.book_id,
        fc.course_code,
        fc.book_title,
        fc.chapter_title,
        fc.section_title,
        fc.start_page,
        fc.end_page
    FROM filtered_real_chapters fc
    WHERE fc.start_page IS NOT NULL 
      AND fc.end_page IS NOT NULL 
      AND (fc.end_page - fc.start_page + 1) >= 5 -- Guard against 1-page stubs
    ON CONFLICT (book_id, section_title) DO UPDATE
    SET 
        start_page = EXCLUDED.start_page,
        end_page = EXCLUDED.end_page,
        chapter_title = EXCLUDED.chapter_title,
        updated_at = now()
    WHERE book_flashcard_progress.status = 'pending';

    GET DIAGNOSTICS v_inserted_count = ROW_COUNT;
    RETURN v_inserted_count;
END;


-- Function: acquire_next_flashcard_job

#variable_conflict use_column
DECLARE
    v_job_id UUID;
BEGIN
    -- Populate top-level chapter jobs into queue
    PERFORM public.initialize_book_flashcard_jobs();

    -- Lock next pending chapter job sequentially
    WITH active_target_book AS (
        SELECT bfp_book.book_id
        FROM public.book_flashcard_progress bfp_book
        WHERE (bfp_book.status = 'pending' OR bfp_book.status = 'failed' OR (bfp_book.status = 'processing' AND bfp_book.locked_until < now()))
        ORDER BY bfp_book.book_title ASC, bfp_book.book_id ASC
        LIMIT 1
    )
    SELECT bfp.id
    INTO v_job_id
    FROM public.book_flashcard_progress bfp
    JOIN active_target_book tb ON bfp.book_id = tb.book_id
    WHERE (bfp.status = 'pending' OR bfp.status = 'failed' OR (bfp.status = 'processing' AND bfp.locked_until < now()))
    ORDER BY bfp.start_page ASC
    LIMIT 1
    FOR UPDATE SKIP LOCKED;

    -- Return atomic job with fixed target of 50 flashcards
    IF v_job_id IS NOT NULL THEN
        UPDATE public.book_flashcard_progress
        SET status = 'processing',
            locked_until = now() + INTERVAL '5 minutes',
            updated_at = now()
        WHERE public.book_flashcard_progress.id = v_job_id;

        RETURN QUERY
        SELECT 
            p.id AS job_id,
            p.book_id,
            p.course_code,
            p.book_title,
            p.chapter_title,
            p.section_title,
            p.start_page,
            p.end_page,
            50 AS target_cards
        FROM public.book_flashcard_progress p
        WHERE p.id = v_job_id;
    END IF;
END;


-- Function: record_flashcard_reviews_batch

DECLARE
    v_user_id UUID := auth.uid();
    v_review jsonb;
    v_card_id UUID;
    v_card_type TEXT;
    v_difficulty TEXT;
    v_curr_interval NUMERIC;
    v_curr_factor NUMERIC;
    v_curr_reps INT;
    v_curr_lapses INT;
    v_curr_state TEXT;
    v_new_interval NUMERIC;
    v_new_factor NUMERIC;
    v_new_reps INT;
    v_new_lapses INT;
    v_new_state TEXT;
    v_fuzz NUMERIC;
    v_count INT := 0;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required';
    END IF;

    FOR v_review IN SELECT * FROM jsonb_array_elements(p_reviews)
    LOOP
        v_card_id := (v_review->>'card_id')::UUID;
        v_card_type := COALESCE(v_review->>'card_type', 'course');
        v_difficulty := LOWER(COALESCE(v_review->>'difficulty', 'good'));

        -- Fetch existing SRS memory state
        SELECT interval_days, ease_factor, repetitions, lapses, state
        INTO v_curr_interval, v_curr_factor, v_curr_reps, v_curr_lapses, v_curr_state
        FROM public.user_flashcard_srs
        WHERE user_id = v_user_id AND card_id = v_card_id;

        IF NOT FOUND THEN
            v_curr_interval := 0;
            v_curr_factor := 2.5;
            v_curr_reps := 0;
            v_curr_lapses := 0;
            v_curr_state := 'learning';
        END IF;

        -- 3-Button Full SM-2 Transition Matrix
        IF v_difficulty = 'hard' THEN
            -- Failure / Lapse
            v_new_reps := 0;
            v_new_lapses := v_curr_lapses + 1;
            v_new_factor := GREATEST(1.3, v_curr_factor - 0.20);
            v_new_interval := 1.0; -- Drops back to day-1 review
            v_new_state := 'learning';
        ELSIF v_difficulty = 'good' THEN
            -- Standard Recall Pass
            v_new_reps := v_curr_reps + 1;
            v_new_lapses := v_curr_lapses;
            v_new_factor := v_curr_factor;
            
            IF v_curr_state = 'learning' OR v_curr_reps = 0 THEN
                v_new_interval := 1.0;
                v_new_state := 'review';
            ELSIF v_curr_reps = 1 THEN
                v_new_interval := 3.0;
                v_new_state := 'review';
            ELSE
                v_new_interval := v_curr_interval * v_curr_factor;
                v_new_state := 'review';
            END IF;
        ELSIF v_difficulty = 'easy' THEN
            -- Fast-Track Mastery
            v_new_reps := v_curr_reps + 1;
            v_new_lapses := v_curr_lapses;
            v_new_factor := v_curr_factor + 0.15;
            v_new_state := 'review';

            IF v_curr_state = 'learning' OR v_curr_reps = 0 THEN
                v_new_interval := 4.0;
            ELSIF v_curr_reps = 1 THEN
                v_new_interval := 7.0;
            ELSE
                v_new_interval := v_curr_interval * v_curr_factor * 1.30;
            END IF;
        END IF;

        -- Anti-Clumping Interval Fuzz (Jitter +/- 6% for intervals >= 3 days)
        IF v_new_interval >= 3.0 THEN
            v_fuzz := 1.0 + (random() * 0.12 - 0.06);
            v_new_interval := v_new_interval * v_fuzz;
        END IF;

        -- Clamp max interval to 5 years (1825 days)
        v_new_interval := LEAST(1825.0, v_new_interval);

        -- Atomic Upsert
        INSERT INTO public.user_flashcard_srs (
            user_id, card_id, card_type, interval_days, ease_factor,
            repetitions, lapses, state, last_reviewed_at, next_review_at
        ) VALUES (
            v_user_id, v_card_id, v_card_type, v_new_interval, v_new_factor,
            v_new_reps, v_new_lapses, v_new_state, NOW(),
            NOW() + (v_new_interval || ' days')::interval
        )
        ON CONFLICT (user_id, card_id) DO UPDATE SET
            interval_days = EXCLUDED.interval_days,
            ease_factor = EXCLUDED.ease_factor,
            repetitions = EXCLUDED.repetitions,
            lapses = EXCLUDED.lapses,
            state = EXCLUDED.state,
            last_reviewed_at = EXCLUDED.last_reviewed_at,
            next_review_at = EXCLUDED.next_review_at;

        v_count := v_count + 1;
    END LOOP;

    RETURN jsonb_build_object('success', true, 'flushed', v_count);
END;


-- Function: get_srs_session_queue

DECLARE
    v_user_id UUID := auth.uid();
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required';
    END IF;

    IF p_is_vault OR p_course_code = 'VAULT' THEN
        -- Synthesize Mistake Vault: Exam Mistakes + Mistake-flagged Miron Cards
        RETURN QUERY
        SELECT 
            card.id, card.front, card.back, card.ref, card.chapter_title, card.is_mistake,
            card.due_status, card.interval_days, card.ease_factor, card.repetitions
        FROM (
            SELECT 
                umf.id,
                umf.front,
                umf.back,
                COALESCE(umf.reference_info, 'Exam Mistake Review') as ref,
                COALESCE(umf.course_code, 'Mistake Vault') as chapter_title,
                true as is_mistake,
                CASE 
                    WHEN srs.card_id IS NULL THEN 'new'
                    WHEN srs.next_review_at <= NOW() THEN 'overdue'
                    ELSE 'future'
                END as due_status,
                COALESCE(srs.interval_days, 0) as interval_days,
                COALESCE(srs.ease_factor, 2.5) as ease_factor,
                COALESCE(srs.repetitions, 0) as repetitions,
                COALESCE(srs.next_review_at, umf.created_at) as sort_date,
                CASE 
                    WHEN srs.next_review_at <= NOW() THEN 1
                    WHEN srs.card_id IS NULL THEN 2
                    ELSE 3
                END as priority_bucket
            FROM public.user_mistake_flashcards umf
            LEFT JOIN public.user_flashcard_srs srs 
                   ON srs.card_id = umf.id AND srs.user_id = v_user_id
            WHERE umf.user_id = v_user_id

            UNION ALL

            SELECT 
                ucf.id,
                ucf.front,
                ucf.back,
                COALESCE(ucf.ref, 'Miron AI • Mistake Review') as ref,
                COALESCE(ucf.course_code, 'Mistake Vault') as chapter_title,
                true as is_mistake,
                CASE 
                    WHEN srs.card_id IS NULL THEN 'new'
                    WHEN srs.next_review_at <= NOW() THEN 'overdue'
                    ELSE 'future'
                END as due_status,
                COALESCE(srs.interval_days, 0) as interval_days,
                COALESCE(srs.ease_factor, 2.5) as ease_factor,
                COALESCE(srs.repetitions, 0) as repetitions,
                COALESCE(srs.next_review_at, ucf.created_at) as sort_date,
                CASE 
                    WHEN srs.next_review_at <= NOW() THEN 1
                    WHEN srs.card_id IS NULL THEN 2
                    ELSE 3
                END as priority_bucket
            FROM public.user_custom_flashcards ucf
            LEFT JOIN public.user_flashcard_srs srs 
                   ON srs.card_id = ucf.id AND srs.user_id = v_user_id
            WHERE ucf.user_id = v_user_id AND ucf.is_mistake = true
        ) card
        ORDER BY card.priority_bucket ASC, card.sort_date ASC
        LIMIT p_limit;
    ELSE
        -- Synthesize Course Decks: Public Cards + User's Miron AI Cards for this Course
        RETURN QUERY
        SELECT 
            card.id, card.front, card.back, card.ref, card.chapter_title, card.is_mistake,
            card.due_status, card.interval_days, card.ease_factor, card.repetitions
        FROM (
            SELECT 
                cf.id,
                cf.front,
                cf.back,
                CASE 
                    WHEN cf.ref_page IS NOT NULL THEN 'Page ' || cf.ref_page
                    ELSE COALESCE(cf.section_title, 'Textbook Section')
                END as ref,
                COALESCE(cf.chapter_title, cf.course_code) as chapter_title,
                false as is_mistake,
                CASE 
                    WHEN srs.card_id IS NULL THEN 'new'
                    WHEN srs.next_review_at <= NOW() THEN 'overdue'
                    ELSE 'future'
                END as due_status,
                COALESCE(srs.interval_days, 0) as interval_days,
                COALESCE(srs.ease_factor, 2.5) as ease_factor,
                COALESCE(srs.repetitions, 0) as repetitions,
                COALESCE(srs.next_review_at, '9999-12-31'::timestamptz) as sort_date,
                CASE 
                    WHEN srs.next_review_at <= NOW() THEN 1
                    WHEN srs.card_id IS NULL THEN 2
                    ELSE 3
                END as priority_bucket,
                cf.ref_page as page_num
            FROM public.course_flashcards cf
            LEFT JOIN public.user_flashcard_srs srs 
                   ON srs.card_id = cf.id AND srs.user_id = v_user_id
            WHERE cf.course_code = p_course_code
              AND (p_chapter_title IS NULL OR cf.chapter_title = p_chapter_title)

            UNION ALL

            SELECT 
                ucf.id,
                ucf.front,
                ucf.back,
                COALESCE(ucf.ref, 'Miron AI • Synthesis') as ref,
                COALESCE(ucf.chapter_title, ucf.course_code) as chapter_title,
                false as is_mistake,
                CASE 
                    WHEN srs.card_id IS NULL THEN 'new'
                    WHEN srs.next_review_at <= NOW() THEN 'overdue'
                    ELSE 'future'
                END as due_status,
                COALESCE(srs.interval_days, 0) as interval_days,
                COALESCE(srs.ease_factor, 2.5) as ease_factor,
                COALESCE(srs.repetitions, 0) as repetitions,
                COALESCE(srs.next_review_at, '9999-12-31'::timestamptz) as sort_date,
                CASE 
                    WHEN srs.next_review_at <= NOW() THEN 1
                    WHEN srs.card_id IS NULL THEN 2
                    ELSE 3
                END as priority_bucket,
                99999 as page_num
            FROM public.user_custom_flashcards ucf
            LEFT JOIN public.user_flashcard_srs srs 
                   ON srs.card_id = ucf.id AND srs.user_id = v_user_id
            WHERE ucf.user_id = v_user_id 
              AND ucf.course_code = p_course_code
              AND (p_chapter_title IS NULL OR ucf.chapter_title = p_chapter_title)
        ) card
        ORDER BY card.priority_bucket ASC, card.sort_date ASC, card.page_num ASC NULLS LAST
        LIMIT p_limit;
    END IF;
END;


-- Function: save_miron_flashcards

DECLARE
    v_user_id UUID := auth.uid();
    v_card jsonb;
    v_id UUID;
    v_front TEXT;
    v_back TEXT;
    v_course_code TEXT;
    v_chapter_title TEXT;
    v_ref TEXT;
    v_is_mistake BOOLEAN;
    v_result jsonb := '[]'::jsonb;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required';
    END IF;

    FOR v_card IN SELECT * FROM jsonb_array_elements(p_cards)
    LOOP
        v_front := v_card->>'front';
        v_back := v_card->>'back';
        v_course_code := UPPER(COALESCE(v_card->>'course_code', 'GENERAL'));
        v_chapter_title := COALESCE(v_card->>'topic', v_card->>'chapter_title', 'Miron Synthesis');
        v_ref := COALESCE(v_card->>'ref', 'Miron AI • Dialogue');
        v_is_mistake := COALESCE((v_card->>'is_mistake')::boolean, false);

        INSERT INTO public.user_custom_flashcards (
            user_id, course_code, chapter_title, front, back, ref, is_mistake
        ) VALUES (
            v_user_id, v_course_code, v_chapter_title, v_front, v_back, v_ref, v_is_mistake
        ) RETURNING id INTO v_id;

        -- Seed initial SRS memory record (0-day initial step)
        INSERT INTO public.user_flashcard_srs (
            user_id, card_id, card_type, interval_days, ease_factor,
            repetitions, lapses, state, last_reviewed_at, next_review_at
        ) VALUES (
            v_user_id, v_id, 'custom', 0, 2.5, 0, 0, 'learning', NOW(), NOW()
        ) ON CONFLICT (user_id, card_id) DO NOTHING;

        v_result := v_result || jsonb_build_object(
            'id', v_id,
            'front', v_front,
            'back', v_back,
            'course_code', v_course_code,
            'topic', v_chapter_title,
            'ref', v_ref,
            'is_mistake', v_is_mistake
        );
    END LOOP;

    RETURN v_result;
END;


-- Function: safe_cast_int

    SELECT CASE 
        WHEN val IS NOT NULL AND val ~ '^\s*[0-9]+\s*$' THEN val::int 
        ELSE default_val 
    END;


-- Function: get_table_counts

DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT t.table_name
        FROM information_schema.tables t
        WHERE t.table_schema = 'public'
          AND t.table_name IN (
            'amharic_dictionary_final',
            'purified_amharic_dictionary',
            'flat_source_metadata',
            'union_refined_dictionary',
            'processed_words',
            'candidate_words',
            'candidate_words_imp6',
            'tele_analysis',
            'verse_analysis',
            'lonely_roots_inspection'
          )
    LOOP
        EXECUTE format(
            'SELECT %L, count(*) FROM public.%I',
            r.table_name, r.table_name
        )
        INTO table_name, row_count;

        RETURN NEXT;
    END LOOP;
END;


-- Function: get_student_academic_pacing

DECLARE
    v_user_id UUID := COALESCE(p_user_id, auth.uid());
    v_dept TEXT;
    v_stream TEXT;
    v_year TEXT;
    v_courses jsonb := '[]'::jsonb;
    v_book RECORD;
    v_user_page INT;
    v_first_page INT;
    v_first_title TEXT;
    v_current_title TEXT;
    v_chapter_start_page INT;
    v_reading_seconds INT;
    v_total_pages INT;
    v_status TEXT;
    v_priority BOOLEAN;
    v_msg TEXT;
    v_threshold_seconds INT := 300; -- 5 Minutes of cumulative reading required
    v_max_plausible_pages INT;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('courses', '[]'::jsonb);
    END IF;

    -- Fetch user's academic classification
    SELECT department, freshman_stream, year
    INTO v_dept, v_stream, v_year
    FROM public.profiles
    WHERE id = v_user_id;

    -- Iterate active curriculum books
    FOR v_book IN 
        SELECT 
            b.id, 
            b.title, 
            b.course_code, 
            b.toc, 
            COALESCE(b.page_offset, 0) AS page_offset
        FROM public.books b
        WHERE b.course_code IS NOT NULL
        ORDER BY b.title ASC
    LOOP
        -- 1. Identify baseline Chapter 1 / Section 1 from TOC
        v_first_page := 1;
        v_first_title := 'Chapter 1';

        IF v_book.toc IS NOT NULL AND jsonb_typeof(v_book.toc) = 'array' AND jsonb_array_length(v_book.toc) > 0 THEN
            SELECT 
                GREATEST(1, COALESCE(public.safe_cast_int(elem->>'page', 1), 1) + v_book.page_offset),
                COALESCE(elem->>'title', 'Chapter 1')
            INTO v_first_page, v_first_title
            FROM jsonb_array_elements(v_book.toc) elem
            WHERE (elem->>'page') ~ '^[0-9]+$'
            ORDER BY COALESCE(public.safe_cast_int(elem->>'page', 1), 1) ASC
            LIMIT 1;
        END IF;

        -- 2. Query legitimate user progress
        SELECT 
            current_page,
            COALESCE(reading_seconds, 0),
            COALESCE(section_title, chapter_title, v_first_title)
        INTO v_user_page, v_reading_seconds, v_current_title
        FROM public.user_course_progress
        WHERE user_id = v_user_id 
          AND book_id = v_book.id;

        -- Check engagement threshold
        IF v_user_page IS NULL OR v_reading_seconds < v_threshold_seconds THEN
            -- Fresh learner: default strictly to Chapter 1
            v_user_page := v_first_page;
            v_current_title := v_first_title;
            v_status := 'not_started';
            v_priority := false;
            v_msg := 'Start with ' || v_first_title || '.';
        ELSE
            -- 3. Scrubber Sanity Check (Allow generous reading speed of 8 seconds per page minimum)
            v_max_plausible_pages := GREATEST(10, v_reading_seconds / 8);
            IF (v_user_page - v_first_page) > v_max_plausible_pages THEN
                -- Scrubber detected: clamp to furthest plausible progress
                v_user_page := v_first_page + (v_reading_seconds / 25);
            END IF;

            -- 4. Map user page to active TOC Chapter Anchor
            v_chapter_start_page := v_first_page;
            IF v_book.toc IS NOT NULL AND jsonb_typeof(v_book.toc) = 'array' AND jsonb_array_length(v_book.toc) > 0 THEN
                SELECT 
                    COALESCE(elem->>'title', v_first_title),
                    GREATEST(1, COALESCE(public.safe_cast_int(elem->>'page', 1), 1) + v_book.page_offset)
                INTO v_current_title, v_chapter_start_page
                FROM jsonb_array_elements(v_book.toc) elem
                WHERE (elem->>'page') ~ '^[0-9]+$'
                  AND GREATEST(1, COALESCE(public.safe_cast_int(elem->>'page', 1), 1) + v_book.page_offset) <= v_user_page
                ORDER BY GREATEST(1, COALESCE(public.safe_cast_int(elem->>'page', 1), 1) + v_book.page_offset) DESC
                LIMIT 1;
            END IF;

            -- 5. Completion evaluation
            SELECT COALESCE(MAX(page_number), 99999)
            INTO v_total_pages
            FROM public.book_pages
            WHERE book_id = v_book.id;

            IF v_user_page >= v_total_pages THEN
                v_status := 'completed';
                v_priority := false;
                v_msg := 'Course book completed. Practice in Exam Pavilion.';
            ELSE
                v_status := 'in_progress';
                v_priority := false;
                v_msg := 'Continue reading ' || v_current_title || ' (page ' || v_user_page || ').';
            END IF;
        END IF;

        -- 6. Construct pacing payload
        v_courses := v_courses || jsonb_build_object(
            'book_id', v_book.id,
            'book_title', v_book.title,
            'course_code', v_book.course_code,
            'status', v_status,
            'expected_chapter_index', 1,
            'expected_chapter_page', v_first_page,
            'user_current_page', v_user_page,
            'is_priority', v_priority,
            'recommendation_msg', v_msg
        );
    END LOOP;

    RETURN jsonb_build_object('courses', v_courses);
END;


-- Function: get_pending_questions_for_mapping

BEGIN
    RETURN QUERY
    SELECT q.id, q.text, q.question_type, q.options, q.matching_data
    FROM questions q
    JOIN sections s ON q.section_id = s.id
    JOIN exams e ON s.exam_id = e.id
    WHERE e.course_id = p_course_id
      AND NOT EXISTS (
          SELECT 1 
          FROM question_book_mappings qbm 
          WHERE qbm.question_id = q.id 
            AND qbm.book_id = p_book_id 
            AND qbm.status IN ('completed', 'processing')
      )
    ORDER BY q.created_at ASC
    LIMIT p_limit;
END;


-- Function: lease_gemini_api_key

DECLARE
    selected_id bigint;
BEGIN
    SELECT k.id INTO selected_id
    FROM api_keys k
    WHERE k.service = 'gemini'
      AND k.is_active = true
      AND (k.cooldown_until IS NULL OR k.cooldown_until <= NOW())
    ORDER BY k.last_used_at ASC NULLS FIRST
    LIMIT 1
    FOR UPDATE SKIP LOCKED;

    IF selected_id IS NOT NULL THEN
        UPDATE api_keys AS ak
        SET last_used_at = NOW()
        WHERE ak.id = selected_id;

        RETURN QUERY 
        SELECT k.id, k.api_key
        FROM api_keys k
        WHERE k.id = selected_id;
    END IF;
END;


-- Function: complete_embedding_job

BEGIN
    UPDATE public.embedding_progress
    SET status = 'completed',
        locked_until = NULL,
        error_message = NULL,
        updated_at = now()
    WHERE id = p_job_id;
END;


-- Function: fail_embedding_job

BEGIN
    UPDATE public.embedding_progress
    SET status = 'failed',
        locked_until = NULL,
        error_message = p_error,
        updated_at = now()
    WHERE id = p_job_id;
END;


-- Function: find_user_by_any_identity

BEGIN
    RETURN QUERY
    SELECT p.id, p.full_name, p.username, p.avatar_url
    FROM public.profiles p
    WHERE p.id != req_user_id 
    AND p.username ILIKE (search_term || '%')
    LIMIT 5;
END;


-- Function: initialize_book_embedding_jobs

DECLARE
    v_inserted_rows integer := 0;
BEGIN
    -- 1. Clear out any stale records for this book to start fresh
    DELETE FROM public.embedding_progress WHERE book_id = p_book_id;

    -- 2. Extract array blocks natively using WITH ORDINALITY
    INSERT INTO public.embedding_progress (book_id, page_number, block_index, status)
    SELECT 
        p_book_id,
        bp.page_number,
        arr.idx - 1 as block_index, -- Convert 1-based ordinality index to 0-based block index
        'pending'::text
    FROM 
        public.book_pages bp
    CROSS JOIN LATERAL 
        jsonb_array_elements(bp.content_json) WITH ORDINALITY arr(elem, idx)
    WHERE 
        bp.book_id = p_book_id
        AND jsonb_typeof(bp.content_json) = 'array'; -- Safe-guard: ignore malformed columns

    GET DIAGNOSTICS v_inserted_rows = ROW_COUNT;
    RETURN v_inserted_rows;
END;


-- Function: squad_kick_member

DECLARE
    executor_role text;
    target_role text;
BEGIN
    -- 1. Get Executor Role
    SELECT role INTO executor_role FROM public.conversation_members 
    WHERE conversation_id = req_conv_id AND user_id = auth.uid();

    IF executor_role NOT IN ('owner', 'admin') THEN
        RAISE EXCEPTION 'Access Denied: Administrative privileges required.';
    END IF;

    -- 2. Get Target Role
    SELECT role INTO target_role FROM public.conversation_members 
    WHERE conversation_id = req_conv_id AND user_id = req_target_id;

    -- 3. Enforce Hierarchy
    IF target_role = 'owner' THEN
        RAISE EXCEPTION 'Mutiny Prevented: You cannot kick the group owner.';
    END IF;
    IF target_role = 'admin' AND executor_role != 'owner' THEN
        RAISE EXCEPTION 'Hierarchy Violation: Only the owner can kick an admin.';
    END IF;

    DELETE FROM public.conversation_members WHERE conversation_id = req_conv_id AND user_id = req_target_id;
END;


-- Function: cooldown_api_key

BEGIN
    UPDATE public.api_keys
    SET cooldown_until = (now() + interval '5 minutes')
    WHERE id = p_key_id;
END;


-- Function: acquire_embedding_jobs

DECLARE
    v_now timestamp with time zone := now();
BEGIN
    -- Safeguard: Ensure there is at least one active key not in cooldown
    IF NOT EXISTS (
        SELECT 1 FROM public.api_keys 
        WHERE service = 'gemini' AND is_active = true 
          AND (cooldown_until IS NULL OR cooldown_until::timestamp with time zone < v_now)
    ) THEN
        RAISE EXCEPTION 'No active, non-cooled-down Gemini API keys available in key pool.';
    END IF;

    RETURN QUERY
    WITH 
    -- 1. Sort active keys by least recently used
    active_keys AS (
        SELECT 
            id, 
            ak.api_key,
            row_number() OVER (ORDER BY last_used_at ASC NULLS FIRST) - 1 as seq_id,
            count(*) OVER () as total_keys
        FROM public.api_keys ak
        WHERE service = 'gemini' 
          AND is_active = true 
          AND (cooldown_until IS NULL OR cooldown_until::timestamp with time zone < v_now)
    ),
    -- 2. STAGE 1: Lock a batch of blocks using SKIP LOCKED (No window functions here)
    raw_jobs AS (
        SELECT 
            ep.id,
            ep.page_number,
            ep.block_index
        FROM public.embedding_progress ep
        WHERE ep.book_id = p_book_id
          AND (ep.status = 'pending' OR ep.status = 'failed' OR (ep.status = 'processing' AND ep.locked_until < v_now))
        ORDER BY ep.page_number, ep.block_index
        LIMIT p_batch_size
        FOR UPDATE SKIP LOCKED
    ),
    -- 3. STAGE 2: Safely apply window indexing over the locked rows
    locked_jobs AS (
        SELECT 
            rj.id,
            rj.page_number,
            rj.block_index,
            row_number() OVER (ORDER BY rj.page_number, rj.block_index) - 1 as seq_id
        FROM raw_jobs rj
    ),
    -- 4. Mark the locked blocks as processing in the DB for 5 minutes
    update_jobs AS (
        UPDATE public.embedding_progress ep
        SET status = 'processing',
            locked_until = v_now + interval '5 minutes',
            updated_at = v_now
        FROM raw_jobs rj
        WHERE ep.id = rj.id
    ),
    -- 5. Join jobs and keys using modulo mapping
    mapped_assignments AS (
        SELECT 
            lj.id as job_id,
            lj.page_number,
            lj.block_index,
            ak.api_key,
            ak.id as api_key_id
        FROM locked_jobs lj
        JOIN active_keys ak ON (lj.seq_id % ak.total_keys) = ak.seq_id
    ),
    -- 6. Update last_used_at on the keys to rotate them instantly
    update_keys AS (
        UPDATE public.api_keys ak
        SET last_used_at = v_now
        FROM (SELECT DISTINCT ma.api_key_id FROM mapped_assignments ma) u
        WHERE ak.id = u.api_key_id
    )
    SELECT 
        ma.job_id,
        ma.page_number,
        ma.block_index,
        ma.api_key,
        ma.api_key_id
    FROM mapped_assignments ma;
END;


-- Function: handle_new_user

DECLARE
    v_phone text;
BEGIN
    v_phone := COALESCE(new.phone, new.raw_user_meta_data->>'phone');
    IF v_phone IS NOT NULL THEN
        v_phone := replace(v_phone, ' ', '');
        IF v_phone LIKE '0%' THEN
            v_phone := '+251' || substring(v_phone from 2);
        ELSIF v_phone NOT LIKE '+%' THEN
            v_phone := '+' || v_phone;
        END IF;
    END IF;

    INSERT INTO public.profiles (
        id, 
        full_name, 
        avatar_url, 
        username,
        telegram_id,
        telegram_username,
        registered_with_telegram,
        phone,
        level, 
        linkoin_balance
    )
    VALUES (
        new.id,
        COALESCE(new.raw_user_meta_data->>'full_name', 'New Student'),
        new.raw_user_meta_data->>'avatar_url',
        COALESCE(new.raw_user_meta_data->>'username', null),
        NULL,
        NULL,
        false,
        v_phone,
        'Division V', -- Default entry tier
        100
    );
    RETURN new;
END;


-- Function: is_member_of

BEGIN
  RETURN EXISTS (
    SELECT 1 FROM public.conversation_members
    WHERE conversation_id = conv_id AND user_id = auth.uid()
  );
END;


-- Function: update_conv_last_message

BEGIN
  UPDATE public.conversations 
  SET last_message_at = NEW.created_at 
  WHERE id = NEW.conversation_id;
  RETURN NEW;
END;


-- Function: check_self_reply

DECLARE
    target_sender_id uuid;
BEGIN
    -- If there's no reply, just allow it
    IF NEW.reply_to_id IS NULL THEN
        RETURN NEW;
    END IF;

    -- Look up the sender of the original message
    SELECT sender_id INTO target_sender_id FROM public.messages WHERE id = NEW.reply_to_id;

    -- Compare
    IF target_sender_id = NEW.sender_id THEN
        RAISE EXCEPTION 'You cannot reply to your own messages. That is just sad.';
    END IF;

    RETURN NEW;
END;


-- Function: check_username_available

BEGIN
  RETURN NOT EXISTS (SELECT 1 FROM public.profiles WHERE username = req_username);
END;


-- Function: check_email_provider

DECLARE
    found_provider TEXT;
BEGIN
    -- Look into the private auth.users table safely
    SELECT (raw_app_meta_data->>'provider') INTO found_provider
    FROM auth.users
    WHERE email = req_email
    LIMIT 1;

    IF found_provider IS NOT NULL THEN
        RETURN QUERY SELECT TRUE, found_provider;
    ELSE
        RETURN QUERY SELECT FALSE, NULL::TEXT;
    END IF;
END;


-- Function: atomic_unpin_question

BEGIN
    -- If a question is being pinned, unpin all others in this specific live session
    IF NEW.is_pinned = true THEN
        UPDATE public.live_stage_questions
        SET is_pinned = false
        WHERE conversation_id = NEW.conversation_id 
          AND id != NEW.id 
          AND is_pinned = true;
    END IF;
    RETURN NEW;
END;


-- Function: heartbeat_live_session

DECLARE
    v_role text;
    v_metadata jsonb;
BEGIN
    -- Verify the requester's rank in the conversation
    SELECT role INTO v_role 
    FROM public.conversation_members 
    WHERE conversation_id = conv_id AND user_id = req_host_id;

    IF v_role IS NULL OR v_role NOT IN ('owner', 'admin') THEN
        RAISE EXCEPTION 'Access Denied: Only group owners or admins are authorized to host live sessions.';
    END IF;

    -- Fetch current metadata
    SELECT metadata INTO v_metadata FROM public.conversations WHERE id = conv_id;

    -- Initialize live_started_at with the current timestamp ONLY on fresh session starts
    IF NOT (v_metadata ? 'live_started_at') THEN
        v_metadata := jsonb_set(COALESCE(v_metadata, '{}'::jsonb), '{live_started_at}', to_jsonb(now()));
    END IF;

    -- Apply standard live status & heartbeat updates
    v_metadata := jsonb_set(
        jsonb_set(
            jsonb_set(v_metadata, '{is_live}', 'true'::jsonb),
            '{live_host_id}', to_jsonb(req_host_id::text)
        ),
        '{live_status}', '"active"'::jsonb
    );
    
    v_metadata := jsonb_set(v_metadata, '{live_heartbeat}', to_jsonb(now()));

    UPDATE public.conversations 
    SET metadata = v_metadata
    WHERE id = conv_id AND (
        (metadata->>'live_host_id' IS NULL) OR 
        (metadata->>'live_host_id' = req_host_id::text)
    );
END;


-- Function: force_peer_question_defaults_fn

BEGIN
    IF auth.role() = 'authenticated' THEN
        -- Force identity alignment
        NEW.user_id := auth.uid();
    END IF;
    RETURN NEW;
END;


-- Function: unpin_on_message_delete_fn

BEGIN
    UPDATE public.conversations
    SET metadata = metadata - 'pinned_message'
    WHERE id = OLD.conversation_id
      AND metadata->'pinned_message'->>'id' = OLD.id::text;
    RETURN OLD;
END;


-- Function: get_next_api_key

BEGIN
  RETURN QUERY
  SELECT ak.id, ak.api_key
  FROM api_keys ak
  WHERE ak.service = target_service
    AND ak.is_active = true
    AND (ak.cooldown_until IS NULL OR ak.cooldown_until <= NOW())
  ORDER BY ak.last_used_at ASC NULLS FIRST
  LIMIT 1;
END;


-- Function: mark_key_usage

BEGIN
  UPDATE api_keys 
  SET last_used_at = NOW() 
  WHERE id = key_id;
END;


-- Function: set_key_cooldown_rpc

BEGIN
  UPDATE api_keys 
  SET cooldown_until = NOW() + interval '5 minutes' 
  WHERE id = key_id;
END;


-- Function: protect_member_roles

BEGIN
    -- Only apply restrictions to API calls made by users (not server-side scripts)
    IF auth.role() = 'authenticated' THEN
        -- Check if either the role or the mute status is being modified
        IF NEW.role IS DISTINCT FROM OLD.role OR NEW.muted_until IS DISTINCT FROM OLD.muted_until THEN
            -- Only allow the modification if the user performing the action is an admin/owner
            IF NOT EXISTS (
                SELECT 1 FROM public.conversation_members 
                WHERE conversation_id = NEW.conversation_id 
                  AND user_id = auth.uid() 
                  AND role IN ('owner', 'admin')
            ) THEN
                RAISE EXCEPTION 'Security Violation: You do not have permission to alter roles or mute durations.';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END;


-- Function: get_or_create_notes

DECLARE
    conv_id uuid;
BEGIN
    IF req_user_id != auth.uid() THEN
        RAISE EXCEPTION 'Access Denied: You cannot create notes for another user.';
    END IF;

    SELECT c.id INTO conv_id
    FROM public.conversations c
    JOIN public.conversation_members cm ON c.id = cm.conversation_id
    WHERE c.type = 'notes' AND cm.user_id = req_user_id
    LIMIT 1;

    IF conv_id IS NULL THEN
        INSERT INTO public.conversations (type, title, owner_id) VALUES ('notes', 'My Notes', req_user_id) RETURNING id INTO conv_id;
        INSERT INTO public.conversation_members (conversation_id, user_id, role) VALUES (conv_id, req_user_id, 'admin');
    END IF;

    RETURN conv_id;
END;


-- Function: protect_profile_fields

BEGIN
    IF auth.role() = 'authenticated' THEN
        -- Protected Gamification
        NEW.linkoin_balance = OLD.linkoin_balance;
        NEW.level = OLD.level;
        
        -- Protected Pro / Gold Status (Zero-Trust Anti-Spoof Guard)
        NEW.is_pro = OLD.is_pro;
        NEW.pro_expires_at = OLD.pro_expires_at;

        -- Protected Identity & Telegram Trust
        NEW.telegram_id = OLD.telegram_id;
        NEW.telegram_username = OLD.telegram_username;
        NEW.registered_with_telegram = OLD.registered_with_telegram;

        -- Phone Normalization (Enforce consistency on any client updates)
        IF NEW.phone IS NOT NULL THEN
            NEW.phone := replace(NEW.phone, ' ', '');
            IF NEW.phone LIKE '0%' THEN
                NEW.phone := '+251' || substring(NEW.phone from 2);
            ELSIF NEW.phone NOT LIKE '+%' THEN
                NEW.phone := '+' || NEW.phone;
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END;


-- Function: sync_squad_privacy_slug

DECLARE
    base_slug text;
    candidate_slug text;
    counter integer := 1;
BEGIN
    -- If switched to private: Vaporize the slug
    IF NEW.metadata->>'privacy' = 'private' THEN
        NEW.metadata := NEW.metadata - 'slug';
        
    -- If switched to public: Generate a fresh secure slug
    ELSIF (NEW.metadata->>'privacy' = 'public' OR NEW.metadata->>'privacy' IS NULL) AND NOT (NEW.metadata ? 'slug') THEN
        base_slug := regexp_replace(lower(NEW.title), '[^a-z0-9]', '', 'g');
        IF base_slug = '' THEN base_slug := 'squad'; END IF;
        candidate_slug := base_slug;

        WHILE EXISTS (SELECT 1 FROM public.conversations WHERE id != NEW.id AND metadata->>'slug' = candidate_slug) LOOP
            candidate_slug := base_slug || counter::text;
            counter := counter + 1;
        END LOOP;
        
        NEW.metadata := NEW.metadata || jsonb_build_object('slug', candidate_slug);
    END IF;
    RETURN NEW;
END;


-- Function: create_direct_message

DECLARE
  new_conv_id UUID;
  existing_conv_id UUID;
  recent_dm_count INTEGER;
BEGIN
  -- 1. Prevent concurrent creation of duplicate DMs
  SELECT c.id INTO existing_conv_id
  FROM public.conversations c
  JOIN public.conversation_members cm1 ON c.id = cm1.conversation_id
  JOIN public.conversation_members cm2 ON c.id = cm2.conversation_id
  WHERE c.type = 'dm' 
    AND cm1.user_id = auth.uid() 
    AND cm2.user_id = target_user_id
  LIMIT 1;

  IF existing_conv_id IS NOT NULL THEN
      RETURN existing_conv_id;
  END IF;

  -- 2. Anti-Spam: Limit new DMs to 15 per 24 hours to prevent DB blooming
  SELECT COUNT(*) INTO recent_dm_count
  FROM public.conversations c
  JOIN public.conversation_members cm ON c.id = cm.conversation_id
  WHERE c.type = 'dm' 
    AND cm.user_id = auth.uid()
    AND c.created_at > (now() - interval '24 hours');

  IF recent_dm_count >= 15 THEN
      RAISE EXCEPTION 'Anti-Spam limits engaged: You have reached the maximum number of new direct message threads (15) allowed per 24 hours.';
  END IF;

  -- 3. Create DM
  INSERT INTO public.conversations (type) VALUES ('dm') RETURNING id INTO new_conv_id;
  
  INSERT INTO public.conversation_members (conversation_id, user_id) 
  VALUES (new_conv_id, auth.uid()), (new_conv_id, target_user_id);
  
  RETURN new_conv_id;
END;


-- Function: enforce_squad_message_rules

DECLARE
    v_role text;
    v_muted_until timestamp with time zone;
    v_members_can_post boolean;
    v_members_can_poll boolean;
    v_type text;
BEGIN
    SELECT type, 
           COALESCE((metadata->>'members_can_post')::boolean, true),
           COALESCE((metadata->>'members_can_poll')::boolean, true)
    INTO v_type, v_members_can_post, v_members_can_poll 
    FROM public.conversations 
    WHERE id = NEW.conversation_id;

    IF v_type = 'group' THEN
        SELECT role, muted_until INTO v_role, v_muted_until
        FROM public.conversation_members
        WHERE conversation_id = NEW.conversation_id AND user_id = NEW.sender_id;

        IF v_role IS NULL THEN RAISE EXCEPTION 'Access Denied: You are not a member of this squad.'; END IF;
        IF v_muted_until IS NOT NULL AND v_muted_until > now() THEN RAISE EXCEPTION 'Access Denied: You are currently restricted from posting.'; END IF;
        IF v_members_can_post = false AND v_role NOT IN ('owner', 'admin') THEN RAISE EXCEPTION 'Access Denied: Administrators have temporarily disabled posting.'; END IF;

        -- Intercept Poll Attachments and check permissions
        IF NEW.attachments IS NOT NULL AND jsonb_typeof(NEW.attachments) = 'array' THEN
            IF EXISTS (SELECT 1 FROM jsonb_array_elements(NEW.attachments) AS elem WHERE elem->>'type' = 'poll') THEN
                IF v_members_can_poll = false AND v_role NOT IN ('owner', 'admin') THEN
                    RAISE EXCEPTION 'Access Denied: Administrators have disabled polling for members.';
                END IF;
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END;


-- Function: leave_squad

DECLARE
    v_role text;
BEGIN
    SELECT role INTO v_role FROM public.conversation_members
    WHERE conversation_id = req_conv_id AND user_id = auth.uid();
    
    IF v_role = 'owner' THEN
        RAISE EXCEPTION 'Owners cannot leave their own group. You must delete the group instead.';
    END IF;

    DELETE FROM public.conversation_members
    WHERE conversation_id = req_conv_id AND user_id = auth.uid();
END;


-- Function: enforce_forward_privacy

DECLARE
   origin_privacy text;
   origin_type text;
   real_sender_name text;
   real_sender_avatar text;
BEGIN
   IF NEW.forward_meta IS NOT NULL AND NEW.forward_meta->>'original_conversation_id' IS NOT NULL THEN
      -- Fetch the truth about the origin conversation
      SELECT type, metadata->>'privacy' INTO origin_type, origin_privacy
      FROM public.conversations 
      WHERE id = (NEW.forward_meta->>'original_conversation_id')::uuid;

      -- BLOCK 1: Absolute blockade against extracting from private groups
      IF origin_type = 'group' AND origin_privacy = 'private' THEN
         RAISE EXCEPTION 'Access Denied: Cannot forward messages originating from a private group.';
      END IF;

      -- BLOCK 2: Anti-Spoofing. Force overwrite the sender's identity with DB truth.
      IF NEW.forward_meta->>'original_sender_id' IS NOT NULL THEN
          SELECT full_name, avatar_url INTO real_sender_name, real_sender_avatar
          FROM public.profiles
          WHERE id = (NEW.forward_meta->>'original_sender_id')::uuid;

          IF FOUND THEN
              -- Overwrite whatever the client sent with the absolute truth
              NEW.forward_meta := jsonb_set(NEW.forward_meta, '{original_sender_name}', to_jsonb(real_sender_name), true);
              NEW.forward_meta := jsonb_set(NEW.forward_meta, '{original_sender_avatar}', to_jsonb(COALESCE(real_sender_avatar, '')), true);
          ELSE
              -- If sender ID doesn't exist, flag it
              NEW.forward_meta := jsonb_set(NEW.forward_meta, '{original_sender_name}', '"Deleted Account"', true);
          END IF;
      END IF;
   END IF;
   RETURN NEW;
END;


-- Function: squad_ban_member

DECLARE
    executor_role text;
    target_role text;
BEGIN
    SELECT role INTO executor_role FROM public.conversation_members WHERE conversation_id = req_conv_id AND user_id = auth.uid();
    IF executor_role NOT IN ('owner', 'admin') THEN RAISE EXCEPTION 'Access Denied: Administrative privileges required.'; END IF;

    SELECT role INTO target_role FROM public.conversation_members WHERE conversation_id = req_conv_id AND user_id = req_target_id;
    
    IF target_role = 'owner' THEN RAISE EXCEPTION 'Mutiny Prevented: You cannot ban the group owner.'; END IF;
    IF target_role = 'admin' AND executor_role != 'owner' THEN RAISE EXCEPTION 'Hierarchy Violation: Only the owner can ban an admin.'; END IF;

    INSERT INTO public.squad_bans (conversation_id, user_id, banned_until) 
    VALUES (req_conv_id, req_target_id, req_banned_until)
    ON CONFLICT (conversation_id, user_id) 
    DO UPDATE SET banned_until = EXCLUDED.banned_until;
    
    DELETE FROM public.conversation_members WHERE conversation_id = req_conv_id AND user_id = req_target_id;
END;


-- Function: squad_mute_member

DECLARE
    executor_role text;
    target_role text;
BEGIN
    SELECT role INTO executor_role FROM public.conversation_members WHERE conversation_id = req_conv_id AND user_id = auth.uid();
    IF executor_role NOT IN ('owner', 'admin') THEN RAISE EXCEPTION 'Access Denied: Administrative privileges required.'; END IF;

    SELECT role INTO target_role FROM public.conversation_members WHERE conversation_id = req_conv_id AND user_id = req_target_id;
    
    IF target_role = 'owner' THEN RAISE EXCEPTION 'Mutiny Prevented: You cannot mute the group owner.'; END IF;
    IF target_role = 'admin' AND executor_role != 'owner' THEN RAISE EXCEPTION 'Hierarchy Violation: Only the owner can mute an admin.'; END IF;

    UPDATE public.conversation_members SET muted_until = req_muted_until 
    WHERE conversation_id = req_conv_id AND user_id = req_target_id;
END;


-- Function: rate_limit_messages_fn

DECLARE
    recent_count INTEGER;
BEGIN
    IF auth.role() = 'authenticated' THEN
        -- Count how many messages this user sent in the last 60 seconds
        SELECT COUNT(*) INTO recent_count
        FROM public.messages
        WHERE sender_id = NEW.sender_id
        AND created_at > (now() - interval '1 minute');

        -- Cap at 60 messages per minute (1 per second on average is plenty)
        IF recent_count >= 60 THEN
            RAISE EXCEPTION 'Rate Limit Exceeded: You are sending messages too quickly. Please wait a minute.';
        END IF;
    END IF;
    RETURN NEW;
END;


-- Function: enforce_attachment_limits

BEGIN
    -- Check if attachments exist and if the array length exceeds 10
    IF NEW.attachments IS NOT NULL AND jsonb_array_length(NEW.attachments) > 10 THEN
        RAISE EXCEPTION 'Payload Rejected: Maximum of 10 attachments allowed per message.';
    END IF;
    RETURN NEW;
END;


-- Function: reply_to_peer_question

DECLARE
    v_asker_id uuid;
    v_q_title text;
    v_dm_id uuid;
    v_replier_name text;
    v_msg_id uuid;
BEGIN
    -- Locate target
    SELECT user_id, title INTO v_asker_id, v_q_title FROM public.peer_questions WHERE id = req_question_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Question not found'; END IF;
    IF v_asker_id = auth.uid() THEN RAISE EXCEPTION 'Cannot reply to your own question.'; END IF;

    -- Get sender identity
    SELECT full_name INTO v_replier_name FROM public.profiles WHERE id = auth.uid();

    -- Instanciate or grab existing DM via our previous robust RPC
    v_dm_id := public.create_direct_message(v_asker_id);

    -- Insert the quoted message context and the reply, returning the specific message ID
    INSERT INTO public.messages (conversation_id, sender_id, text)
    VALUES (v_dm_id, auth.uid(), 'Replying to your question: "' || v_q_title || '"' || E'\n\n' || req_reply_text)
    RETURNING id INTO v_msg_id;

    -- [NEW]: Increment the real reply counter
    UPDATE public.peer_questions
    SET replies_count = replies_count + 1
    WHERE id = req_question_id;

    -- Fire the refined notification with exact deep-link payload
    INSERT INTO public.notifications (user_id, type, title, description, icon, action_data)
    VALUES (
        v_asker_id, 
        'study', 
        v_replier_name || ' answered your question!', 
        'They replied to your question regarding "' || v_q_title || '".',
        'fa-comment-dots',
        jsonb_build_object('action', 'open_chat', 'conversation_id', v_dm_id, 'message_id', v_msg_id, 'chat_type', 'dm')
    );
END;


-- Function: get_and_rotate_gemini_key

DECLARE
    target_id bigint;
    found_key text;
END_TIME timestamptz;
BEGIN
    -- Select the least-recently-used active 'gemini' key that is not on cooldown
    SELECT id, api_key
    INTO target_id, found_key
    FROM public.api_keys
    WHERE service = 'gemini'
      AND is_active = true
      AND (cooldown_until IS NULL OR cooldown_until <= now())
    ORDER BY last_used_at ASC NULLS FIRST
    LIMIT 1
    FOR UPDATE SKIP LOCKED; -- High-concurrency safety lock

    -- If we successfully found a key, update its last_used_at and return it
    IF found_key IS NOT NULL THEN
        UPDATE public.api_keys
        SET last_used_at = now()
        WHERE id = target_id;
        
        selected_key := found_key;
        RETURN NEXT;
    END IF;
END;


-- Function: cooldown_gemini_key

BEGIN
    -- Put the specific gemini key on cooldown for 5 minutes
    UPDATE public.api_keys
    SET cooldown_until = now() + INTERVAL '5 minutes'
    WHERE api_key = expired_key
      AND service = 'gemini';
END;


-- Function: force_live_question_defaults_fn

BEGIN
    IF auth.role() = 'authenticated' THEN
        -- Strip any malicious auto-approval or pin attempts
        NEW.status := 'pending';
        NEW.is_pinned := false;
        
        -- Strictly force the real sender identity (No identity spoofing!)
        NEW.sender_id := auth.uid();
    END IF;
    RETURN NEW;
END;


-- Function: prevent_msg_tampering_fn

BEGIN
    IF auth.role() = 'authenticated' THEN
        -- Prevent teleportation & impersonation
        IF NEW.conversation_id != OLD.conversation_id THEN RAISE EXCEPTION 'Security Violation: Cannot move messages.'; END IF;
        IF NEW.sender_id != OLD.sender_id THEN RAISE EXCEPTION 'Security Violation: Cannot change sender.'; END IF;
        IF NEW.forward_meta IS DISTINCT FROM OLD.forward_meta THEN RAISE EXCEPTION 'Security Violation: Cannot tamper with forward metadata.'; END IF;
        IF NEW.attachments IS DISTINCT FROM OLD.attachments THEN RAISE EXCEPTION 'Security Violation: Cannot alter message attachments.'; END IF;
        IF NEW.reply_to_id IS DISTINCT FROM OLD.reply_to_id THEN RAISE EXCEPTION 'Security Violation: Cannot alter reply target.'; END IF;
        
        -- Prevent editing messages older than 24 hours & FORCE the is_edited flag
        IF NEW.text IS DISTINCT FROM OLD.text THEN
            IF OLD.created_at < (now() - interval '24 hours') THEN
                RAISE EXCEPTION 'Time Limit Exceeded: Messages cannot be edited after 24 hours.';
            END IF;
            NEW.is_edited := true; -- OVERWRITE CLIENT PAYLOAD
        END IF;
    END IF;
    RETURN NEW;
END;


-- Function: cast_poll_vote

DECLARE
    v_msg record;
    v_poll jsonb;
    v_deadline timestamptz;
    v_allow_revote boolean;
    v_allow_multiple boolean;
    v_has_voted boolean;
BEGIN
    SELECT * INTO v_msg FROM public.messages WHERE id = req_message_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Message not found'; END IF;
    
    -- Extract the poll attachment
    SELECT elem INTO v_poll FROM jsonb_array_elements(v_msg.attachments) AS elem WHERE elem->>'type' = 'poll' LIMIT 1;
    IF v_poll IS NULL THEN RAISE EXCEPTION 'No poll found in this message'; END IF;
    
    -- Load physics settings
    v_deadline := (v_poll->'poll_data'->>'deadline')::timestamptz;
    v_allow_revote := COALESCE((v_poll->'poll_data'->>'allow_revote')::boolean, false);
    v_allow_multiple := COALESCE((v_poll->'poll_data'->>'multiple_answers')::boolean, false);
    
    -- Check temporal boundaries
    IF v_deadline IS NOT NULL AND v_deadline < now() THEN
        RAISE EXCEPTION 'Poll has ended';
    END IF;
    
    -- If single-choice, purge other selections
    IF NOT v_allow_multiple THEN
        DELETE FROM public.poll_votes 
        WHERE message_id = req_message_id AND user_id = auth.uid() AND option_index != req_option_index;
    END IF;
    
    -- Check specific vote existence for toggling
    SELECT EXISTS(SELECT 1 FROM public.poll_votes WHERE message_id = req_message_id AND user_id = auth.uid() AND option_index = req_option_index) INTO v_has_voted;
    
    IF v_has_voted THEN
        IF v_allow_revote THEN
            DELETE FROM public.poll_votes WHERE message_id = req_message_id AND user_id = auth.uid() AND option_index = req_option_index;
        ELSE
            RAISE EXCEPTION 'Revoting is disabled for this poll';
        END IF;
    ELSE
        INSERT INTO public.poll_votes (message_id, user_id, option_index) VALUES (req_message_id, auth.uid(), req_option_index);
    END IF;
END;


-- Function: get_available_books

  SELECT 
    pdf_name, 
    count(*)::int AS completed_pages,
    min(page_number)::int AS min_page,
    max(page_number)::int AS max_page
  FROM book_results
  GROUP BY pdf_name
  ORDER BY pdf_name ASC;


-- Function: check_phone_registered

BEGIN
    RETURN EXISTS (SELECT 1 FROM public.profiles WHERE phone = req_phone);
END;


-- Function: check_phone_link_status

DECLARE
    v_user_id uuid;
    v_email text;
    v_is_transient boolean := false;
    v_local_part text;
    v_domain_part text;
    v_masked_email text;
    v_len int;
BEGIN
    -- 1. Find the profile holding this phone number
    SELECT id INTO v_user_id 
    FROM public.profiles 
    WHERE phone = req_phone 
    LIMIT 1;

    -- If no profile has this phone, it is available
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('exists', false);
    END IF;

    -- 2. Fetch the associated auth email
    SELECT email INTO v_email 
    FROM auth.users 
    WHERE id = v_user_id 
    LIMIT 1;

    IF v_email IS NULL THEN
        RETURN jsonb_build_object('exists', false);
    END IF;

    -- 3. Determine if the account is a transient Telegram-only placeholder
    IF v_email LIKE '%@linkup.invalid' THEN
        v_is_transient := true;
    END IF;

    -- 4. Apply dynamic length-aware masking
    v_local_part := split_part(v_email, '@', 1);
    v_domain_part := split_part(v_email, '@', 2);
    v_len := length(v_local_part);

    IF v_len <= 1 THEN
        v_masked_email := '*@' || v_domain_part;
    ELSIF v_len = 2 THEN
        v_masked_email := left(v_local_part, 1) || '*@' || v_domain_part;
    ELSIF v_len <= 4 THEN
        v_masked_email := left(v_local_part, 1) || repeat('*', v_len - 2) || right(v_local_part, 1) || '@' || v_domain_part;
    ELSE
        -- 5 or more characters: Show first 2, hide middle, show last 2
        v_masked_email := left(v_local_part, 2) || repeat('*', v_len - 4) || right(v_local_part, 2) || '@' || v_domain_part;
    END IF;

    RETURN jsonb_build_object(
        'exists', true,
        'is_transient', v_is_transient,
        'masked_email', v_masked_email
    );
END;


-- Function: acquire_question_answers_jobs

BEGIN
    RETURN QUERY
    WITH locked AS (
        SELECT question_id
        FROM public.question_processing_progress
        WHERE book_id = p_book_id AND status = 'pending'
        LIMIT p_limit
        FOR UPDATE SKIP LOCKED
    )
    UPDATE public.question_processing_progress qpp
    SET status = 'processing', processed_at = now()
    FROM locked
    WHERE qpp.question_id = locked.question_id
    RETURNING qpp.question_id;
END;


-- Function: get_compiled_book

  SELECT coalesce(
    jsonb_object_agg(
      kv.key, 
      kv.value
    ), 
    '{}'::jsonb
  )
  FROM (
    SELECT result_json
    FROM book_results
    WHERE pdf_name = target_pdf
    ORDER BY page_number ASC
  ) sub,
  LATERAL jsonb_each(sub.result_json) kv;


-- Function: get_my_referrals

DECLARE
    result jsonb;
BEGIN
    SELECT jsonb_agg(jsonb_build_object(
        'id', r.id,
        'status', r.status,
        'created_at', r.created_at,
        'referee_name', p.full_name,
        'referee_username', p.username,
        'referee_avatar', p.avatar_url
    ) ORDER BY r.created_at DESC) INTO result
    FROM public.referrals r
    JOIN public.profiles p ON p.id = r.referee_id
    WHERE r.referrer_id = auth.uid();
    
    RETURN COALESCE(result, '[]'::jsonb);
END;


-- Function: sync_linkoin_balance

BEGIN
    UPDATE public.profiles
    SET linkoin_balance = COALESCE(linkoin_balance, 0) + NEW.amount
    WHERE id = NEW.user_id;
    RETURN NEW;
END;


-- Function: claim_telegram_verification_reward

DECLARE
    v_user record;
    v_key text;
BEGIN
    -- 1. Fetch user status and lock the row to prevent concurrent race conditions
    SELECT id, registered_with_telegram INTO v_user
    FROM public.profiles
    WHERE id = auth.uid()
    FOR UPDATE;

    IF v_user IS NULL THEN
        RAISE EXCEPTION 'User not found';
    END IF;

    IF v_user.registered_with_telegram IS NOT TRUE THEN
        RAISE EXCEPTION 'You must verify your Telegram account first.';
    END IF;

    -- 2. Check Idempotency (Has this specific reward already been claimed?)
    v_key := 'tg_verify_reward_' || v_user.id::text;

    IF EXISTS (SELECT 1 FROM public.linkoin_transactions WHERE idempotency_key = v_key) THEN
        RAISE EXCEPTION 'Reward already claimed.';
    END IF;

    -- 3. Insert transaction (Trigger automatically updates balance)
    INSERT INTO public.linkoin_transactions (user_id, amount, transaction_type, description, idempotency_key)
    VALUES (v_user.id, 50, 'reward', 'Telegram Verification Mission', v_key);

    RETURN jsonb_build_object('success', true, 'amount_granted', 50);
END;


-- Function: register_referral

DECLARE
    v_referrer_id UUID;
BEGIN
    -- Resolve the username to an ID
    SELECT id INTO v_referrer_id FROM public.profiles WHERE username = referrer_username LIMIT 1;
    
    IF v_referrer_id IS NOT NULL AND v_referrer_id != auth.uid() THEN
        -- Safely insert the pending referral (ignores if referee already has an inviter)
        INSERT INTO public.referrals (referrer_id, referee_id, status)
        VALUES (v_referrer_id, auth.uid(), 'pending')
        ON CONFLICT (referee_id) DO NOTHING;
    END IF;
END;


-- Function: update_user_streak

DECLARE
    v_today date;
    v_yesterday date;
    v_last_update date;
BEGIN
    v_today := (now() AT TIME ZONE 'Africa/Addis_Ababa')::date;
    v_yesterday := v_today - interval '1 day';
    
    -- Lock row for safety
    SELECT last_streak_update INTO v_last_update
    FROM public.profiles WHERE id = auth.uid() FOR UPDATE;
    
    IF v_last_update IS NULL OR v_last_update < v_yesterday THEN
        -- Streak broken or first ever load
        UPDATE public.profiles 
        SET current_streak = 1, last_streak_update = v_today
        WHERE id = auth.uid();
    ELSIF v_last_update = v_yesterday THEN
        -- Active yesterday, increment!
        UPDATE public.profiles 
        SET current_streak = current_streak + 1,
            longest_streak = GREATEST(longest_streak, current_streak + 1),
            last_streak_update = v_today
        WHERE id = auth.uid();
    END IF;
    -- If v_last_update = v_today, they already checked in. Do nothing.
END;


-- Function: trigger_referral_reward

DECLARE
    v_referral record;
    v_referrer_key text;
BEGIN
    -- Only trigger when registered_with_telegram transitions from false to true
    IF NEW.registered_with_telegram = true AND OLD.registered_with_telegram = false THEN
        
        -- Check if this user was invited by someone
        SELECT * INTO v_referral FROM public.referrals WHERE referee_id = NEW.id AND status = 'pending' LIMIT 1;
        
        IF v_referral IS NOT NULL THEN
            -- 1. Mark as completed
            UPDATE public.referrals SET status = 'completed' WHERE id = v_referral.id;
            
            v_referrer_key := 'ref_bonus_referrer_' || v_referral.referee_id::text;
            
            -- 2. Reward the Referrer (+30). The referee relies on the 100 default coins given on signup.
            INSERT INTO public.linkoin_transactions (user_id, amount, transaction_type, description, idempotency_key)
            VALUES (v_referral.referrer_id, 30, 'reward', 'Squad Network Invite Bonus', v_referrer_key)
            ON CONFLICT (idempotency_key) DO NOTHING;
        END IF;
    END IF;
    RETURN NEW;
END;


-- Function: get_current_streak_mission

DECLARE
    v_semester int;
    v_current int;
    v_target int;
    v_reward int;
    v_claimed boolean;
BEGIN
    SELECT COALESCE((value->>'semester')::int, 1) INTO v_semester FROM public.system_config WHERE key = 'academic_calendar';
    SELECT current_streak INTO v_current FROM public.profiles WHERE id = auth.uid();
    
    -- Progressively scan targets. Stops and returns the FIRST unclaimed one.
    FOREACH v_target IN ARRAY ARRAY[7, 15, 30, 60, 90, 120, 150, 180, 210, 240, 270, 300, 330, 360]
    LOOP
        SELECT EXISTS(
            SELECT 1 FROM public.linkoin_transactions 
            WHERE idempotency_key = 'streak_claim_' || v_target::text || '_' || auth.uid()::text || '_sem' || v_semester::text
        ) INTO v_claimed;
        
        IF NOT v_claimed THEN
            IF v_target = 7 THEN v_reward := 70;
            ELSIF v_target = 15 THEN v_reward := 150;
            ELSIF v_target = 30 THEN v_reward := 300;
            ELSIF v_target = 60 THEN v_reward := 400;
            ELSE v_reward := 500 + (((v_target - 90) / 30) * 100);
            END IF;
            
            RETURN jsonb_build_object(
                'target', v_target, 
                'reward', v_reward, 
                'status', CASE WHEN v_current >= v_target THEN 'claimable' ELSE 'in_progress' END, 
                'current', v_current
            );
        END IF;
    END LOOP;
    RETURN jsonb_build_object('status', 'maxed_out');
END;


-- Function: claim_streak_milestone

DECLARE
    v_semester int;
    v_current int;
    v_reward int;
    v_key text;
BEGIN
    SELECT COALESCE((value->>'semester')::int, 1) INTO v_semester FROM public.system_config WHERE key = 'academic_calendar';
    SELECT current_streak INTO v_current FROM public.profiles WHERE id = auth.uid() FOR UPDATE;
    
    IF v_current < p_target THEN RAISE EXCEPTION 'Streak target not reached yet.'; END IF;
    
    v_key := 'streak_claim_' || p_target::text || '_' || auth.uid()::text || '_sem' || v_semester::text;
    IF EXISTS (SELECT 1 FROM public.linkoin_transactions WHERE idempotency_key = v_key) THEN
        RAISE EXCEPTION 'Milestone already claimed.';
    END IF;
    
    IF p_target = 7 THEN v_reward := 70;
    ELSIF p_target = 15 THEN v_reward := 150;
    ELSIF p_target = 30 THEN v_reward := 300;
    ELSIF p_target = 60 THEN v_reward := 400;
    ELSE v_reward := 500 + (((p_target - 90) / 30) * 100);
    END IF;
    
    INSERT INTO public.linkoin_transactions (user_id, amount, transaction_type, description, idempotency_key)
    VALUES (auth.uid(), v_reward, 'reward', p_target::text || ' Day Streak Bonus', v_key);
    
    RETURN jsonb_build_object('success', true);
END;


-- Function: admin_reset_semester_streaks

DECLARE
    v_old_semester int;
BEGIN
    SELECT COALESCE((value->>'semester')::int, 1) INTO v_old_semester FROM public.system_config WHERE key = 'academic_calendar';
    
    UPDATE public.system_config SET value = jsonb_build_object('semester', v_old_semester + 1) WHERE key = 'academic_calendar';
    UPDATE public.profiles SET current_streak = 0, last_streak_update = NULL;
END;


-- Function: check_squad_slug_available

BEGIN
  RETURN NOT EXISTS (SELECT 1 FROM public.conversations WHERE metadata->>'slug' = req_slug);
END;


-- Function: create_study_group

DECLARE
  base_slug text;
  candidate_slug text;
  counter integer := 1;
  new_conv_id uuid;
  final_metadata jsonb;
  owned_count integer;
BEGIN
  SELECT count(*) INTO owned_count 
  FROM public.conversations 
  WHERE owner_id = auth.uid() AND type = 'group';
  
  IF owned_count >= 3 THEN
      RAISE EXCEPTION 'Limit reached. You can only own up to 3 study groups/classes.';
  END IF;

  final_metadata := COALESCE(req_metadata, '{}'::jsonb);

  IF (final_metadata->>'privacy' IS NULL OR final_metadata->>'privacy' = 'public') THEN
      IF final_metadata ? 'slug' AND final_metadata->>'slug' != '' THEN
          base_slug := final_metadata->>'slug';
      ELSE
          base_slug := regexp_replace(lower(req_title), '[^a-z0-9]', '', 'g');
          IF base_slug = '' THEN base_slug := 'squad'; END IF;
      END IF;
      candidate_slug := base_slug;
      LOOP
        WHILE EXISTS (SELECT 1 FROM public.conversations WHERE metadata->>'slug' = candidate_slug) LOOP
          candidate_slug := base_slug || counter::text;
          counter := counter + 1;
        END LOOP;
        BEGIN
          final_metadata := final_metadata || jsonb_build_object('slug', candidate_slug);
          INSERT INTO public.conversations (type, title, metadata, owner_id)
          VALUES ('group', req_title, final_metadata, auth.uid())
          RETURNING id INTO new_conv_id;
          EXIT; 
        EXCEPTION WHEN unique_violation THEN
          candidate_slug := base_slug || counter::text;
          counter := counter + 1;
        END;
      END LOOP;
  ELSE
      INSERT INTO public.conversations (type, title, metadata, owner_id)
      VALUES ('group', req_title, final_metadata, auth.uid())
      RETURNING id INTO new_conv_id;
  END IF;

  INSERT INTO public.conversation_members (conversation_id, user_id, role)
  VALUES (new_conv_id, auth.uid(), 'owner');

  RETURN new_conv_id;
END;


-- Function: check_profile_class_membership

BEGIN
    IF NEW.class_id IS NOT NULL THEN
        IF NOT EXISTS (
            SELECT 1 FROM public.conversation_members 
            WHERE conversation_id = NEW.class_id AND user_id = NEW.id
        ) THEN
            RAISE EXCEPTION 'You must join the class group before linking it to your profile.';
        END IF;
    END IF;
    RETURN NEW;
END;


-- Function: handle_member_leave_or_kick

BEGIN
    UPDATE public.profiles
    SET class_id = NULL
    WHERE id = OLD.user_id AND class_id = OLD.conversation_id;
    RETURN OLD;
END;


-- Function: get_social_discovery

DECLARE
    my_uni UUID;
    my_dept TEXT;
BEGIN
    SELECT p.university_id, p.department INTO my_uni, my_dept
    FROM public.profiles p WHERE p.id = req_user_id;

    RETURN QUERY
    SELECT 
        p.id, p.full_name, p.username, p.avatar_url, p.university_id, p.department,
        CASE 
            WHEN p.university_id = my_uni AND p.department = my_dept THEN 1
            WHEN p.university_id = my_uni THEN 2
            ELSE 3
        END as tier
    FROM public.profiles p
    WHERE p.id != req_user_id
    AND p.id NOT IN (
        SELECT cm2.user_id
        FROM public.conversation_members cm1
        JOIN public.conversation_members cm2 ON cm1.conversation_id = cm2.conversation_id
        JOIN public.conversations c ON cm1.conversation_id = c.id
        WHERE cm1.user_id = req_user_id AND cm2.user_id != req_user_id AND c.type = 'dm'
    )
    ORDER BY tier ASC, p.last_seen_at DESC NULLS LAST
    LIMIT 30;
END;


-- Function: join_study_group

DECLARE
    ban_record RECORD;
    conv_privacy text;
    db_token text;
BEGIN
    IF req_user_id != auth.uid() THEN
        RAISE EXCEPTION 'Access Denied: You cannot force another user to join a group.';
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.conversation_members 
        WHERE conversation_id = req_conversation_id AND user_id = req_user_id
    ) THEN
        RETURN;
    END IF;

    -- Privacy verification block
    SELECT metadata->>'privacy', metadata->>'private_invite_token' INTO conv_privacy, db_token 
    FROM public.conversations WHERE id = req_conversation_id;
    
    IF conv_privacy = 'private' THEN
        IF req_token IS NULL OR req_token != db_token THEN
            RAISE EXCEPTION 'Access Denied: This group is private or the invite link is invalid.';
        END IF;
    END IF;

    SELECT banned_until INTO ban_record FROM public.squad_bans WHERE conversation_id = req_conversation_id AND user_id = req_user_id;
    IF FOUND THEN
        IF ban_record.banned_until IS NULL OR ban_record.banned_until > now() THEN
            RAISE EXCEPTION 'Access Denied: You are banned from this group.';
        ELSE
            DELETE FROM public.squad_bans WHERE conversation_id = req_conversation_id AND user_id = req_user_id;
        END IF;
    END IF;

    INSERT INTO public.conversation_members (conversation_id, user_id, role)
    VALUES (req_conversation_id, req_user_id, 'member')
    ON CONFLICT DO NOTHING;
END;


-- Function: create_private_invite_link

DECLARE
    v_role text;
    new_token text;
    current_meta jsonb;
BEGIN
    SELECT role INTO v_role FROM public.conversation_members WHERE conversation_id = req_conv_id AND user_id = auth.uid();
    IF v_role != 'owner' THEN
        RAISE EXCEPTION 'Access Denied: Only the owner can generate an invite link.';
    END IF;

    new_token := substring(md5(random()::text), 1, 16);
    
    SELECT metadata INTO current_meta FROM public.conversations WHERE id = req_conv_id;
    current_meta := jsonb_set(COALESCE(current_meta, '{}'::jsonb), '{private_invite_token}', to_jsonb(new_token));

    UPDATE public.conversations SET metadata = current_meta WHERE id = req_conv_id;
    RETURN new_token;
END;


-- Function: revoke_private_invite_link

DECLARE
    v_role text;
    current_meta jsonb;
BEGIN
    SELECT role INTO v_role FROM public.conversation_members WHERE conversation_id = req_conv_id AND user_id = auth.uid();
    IF v_role != 'owner' THEN
        RAISE EXCEPTION 'Access Denied: Only the owner can revoke an invite link.';
    END IF;
    
    SELECT metadata INTO current_meta FROM public.conversations WHERE id = req_conv_id;
    current_meta := current_meta - 'private_invite_token';

    UPDATE public.conversations SET metadata = current_meta WHERE id = req_conv_id;
END;


-- Function: get_private_group_by_token

DECLARE
    group_record record;
    member_count int;
    user_is_member boolean;
BEGIN
    SELECT id, title, avatar_url, metadata
    INTO group_record
    FROM public.conversations
    WHERE metadata->>'private_invite_token' = req_token AND type = 'group';

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Invalid or expired invitation link.';
    END IF;

    -- Tally the current roster
    SELECT count(*) INTO member_count FROM public.conversation_members WHERE conversation_id = group_record.id;
    
    -- Evaluate the requester's membership silently
    SELECT EXISTS(SELECT 1 FROM public.conversation_members WHERE conversation_id = group_record.id AND user_id = auth.uid()) INTO user_is_member;

    RETURN jsonb_build_object(
        'id', group_record.id,
        'title', group_record.title,
        'avatar_url', group_record.avatar_url,
        'focus', group_record.metadata->>'focus',
        'member_count', member_count,
        'is_member', user_is_member
    );
END;


-- Function: kill_live_session

DECLARE
    v_role text;
    v_host_id text;
BEGIN
    -- 1. Identify who is currently hosting
    SELECT metadata->>'live_host_id' INTO v_host_id 
    FROM public.conversations WHERE id = conv_id;
    
    -- 2. Identify the rank of the person trying to kill the session
    SELECT role INTO v_role 
    FROM public.conversation_members 
    WHERE conversation_id = conv_id AND user_id = auth.uid();

    -- 3. The Law: You can only kill it if you are the active host, OR an Admin/Owner
    IF auth.uid()::text != v_host_id AND (v_role IS NULL OR v_role NOT IN ('owner', 'admin')) THEN
        RAISE EXCEPTION 'Security Violation: You are not authorized to terminate this broadcast.';
    END IF;

    -- 4. Execute the safe cleanup of metadata
    UPDATE public.conversations 
    SET metadata = metadata - 'is_live' - 'live_host_id' - 'live_status' - 'live_heartbeat' - 'live_started_at'
    WHERE id = conv_id;

    -- 5. CRITICAL FIX: Purge the discovery engine record so it disappears from the global 'Explore' feed instantly
    DELETE FROM public.live_study_sessions WHERE conversation_id = conv_id;
END;


-- Function: get_live_study_sessions

DECLARE
    my_uni uuid;
    my_stream text;
BEGIN
    -- Get the viewer's academic profile
    SELECT university_id, freshman_stream INTO my_uni, my_stream
    FROM public.profiles WHERE profiles.id = req_user_id;

    RETURN QUERY
    WITH EligibleSessions AS (
        SELECT s.id, s.conversation_id, s.course_name, s.lesson_topic, s.active_user_ids, s.last_updated_at
        FROM public.live_study_sessions s
        JOIN public.conversations c ON s.conversation_id = c.id
        WHERE 
        -- Exclude Private Groups completely
        (c.metadata->>'privacy' = 'public' OR c.metadata->>'privacy' IS NULL)
        -- Only consider sessions active in the last 2 hours
        AND s.last_updated_at > now() - interval '2 hours'
        AND
        -- Miron's Intelligent Course Routing Filter (Safely handles NULL streams)
        CASE 
            WHEN s.course_name ILIKE ANY(ARRAY['%Biology%', '%Chemistry%', '%Physics%']) THEN COALESCE(my_stream, '') = 'Natural Science'
            WHEN s.course_name ILIKE ANY(ARRAY['%Geography%', '%History%', '%Anthropology%']) THEN COALESCE(my_stream, '') = 'Social Science'
            ELSE TRUE 
        END
    ),
    SessionStats AS (
        SELECT 
            es.id AS sid,
            -- Tally exact relational proximity 
            (SELECT count(*) FROM public.profiles p WHERE p.id = ANY(es.active_user_ids) AND p.id != req_user_id AND p.university_id = my_uni AND p.freshman_stream = my_stream) AS classmates_count,
            (SELECT count(*) FROM public.profiles p WHERE p.id = ANY(es.active_user_ids) AND p.id != req_user_id AND p.university_id = my_uni AND p.freshman_stream != my_stream) AS campus_mates_count,
            (SELECT count(*) FROM public.profiles p WHERE p.id = ANY(es.active_user_ids) AND p.id != req_user_id AND p.university_id != my_uni AND p.freshman_stream = my_stream) AS scholars_count,
            
            -- FIX: Count EVERYONE using native array length so RLS doesn't block the count
            cardinality(es.active_user_ids) AS total_count
        FROM EligibleSessions es
    )
    SELECT 
        es.id,
        es.conversation_id,
        es.course_name,
        es.lesson_topic,
        -- The Dynamic Text Engine
        CASE
            WHEN ss.classmates_count > 0 THEN 
                ss.classmates_count::text || ' classmates from your stream are studying this right now. Join and share notes!'
            WHEN ss.campus_mates_count > 0 THEN 
                ss.campus_mates_count::text || ' students from your campus are studying this right now. Join and share notes!'
            WHEN ss.scholars_count > 0 THEN 
                ss.scholars_count::text || ' freshman scholars from other universities are studying this right now.'
            ELSE 
                ss.total_count::text || ' students are studying this right now. Join the session!'
        END AS dynamic_message,
        ss.total_count::integer AS participant_count,
        es.last_updated_at
    FROM EligibleSessions es
    JOIN SessionStats ss ON es.id = ss.sid
    WHERE ss.total_count > 0
    ORDER BY ss.classmates_count DESC, ss.total_count DESC, es.last_updated_at DESC
    LIMIT 5;
END;


-- Function: global_network_search

BEGIN
    RETURN QUERY
    -- 1. Search Users
    SELECT 
        p.id,
        'user'::TEXT AS type,
        p.full_name AS title,
        p.username AS subtitle,
        p.avatar_url,
        '{}'::JSONB AS metadata,
        EXISTS (
            SELECT 1 FROM conversation_members cm1
            JOIN conversation_members cm2 ON cm1.conversation_id = cm2.conversation_id
            JOIN conversations c ON cm1.conversation_id = c.id
            WHERE c.type = 'dm' AND cm1.user_id = req_user_id AND cm2.user_id = p.id
        ) AS is_member
    FROM public.profiles p
    WHERE p.id != req_user_id
      AND (p.full_name ILIKE ('%' || search_term || '%') OR p.username ILIKE ('%' || search_term || '%'))
    
    UNION ALL
    
    -- 2. Search Groups
    SELECT 
        c.id,
        'group'::TEXT AS type,
        c.title,
        COALESCE(c.metadata->>'focus', 'General') AS subtitle,
        c.avatar_url,
        COALESCE(c.metadata, '{}'::jsonb) AS metadata,
        EXISTS (
            SELECT 1 FROM conversation_members cm WHERE cm.conversation_id = c.id AND cm.user_id = req_user_id
        ) AS is_member
    FROM public.conversations c
    WHERE c.type = 'group'
      AND c.title ILIKE ('%' || search_term || '%')
      AND (
          (c.metadata->>'privacy' = 'public' OR c.metadata->>'privacy' IS NULL)
          OR 
          EXISTS (SELECT 1 FROM conversation_members cm WHERE cm.conversation_id = c.id AND cm.user_id = req_user_id)
      )
    LIMIT 30;
END;


-- Function: get_suggested_squads

BEGIN
    RETURN QUERY
    SELECT 
        c.id AS conversation_id,
        c.title::text AS title,
        COALESCE(c.metadata, '{}'::jsonb) AS metadata,
        (SELECT COUNT(*) FROM public.conversation_members cm WHERE cm.conversation_id = c.id)::integer AS m_count
    FROM public.conversations c
    WHERE c.type::text = 'group'
      AND (c.metadata->>'focus' IS DISTINCT FROM 'Class')
      AND (c.metadata->>'privacy' = 'public' OR c.metadata->>'privacy' IS NULL)
      AND NOT EXISTS (
          SELECT 1 FROM public.conversation_members cm2 
          WHERE cm2.conversation_id = c.id AND cm2.user_id = req_user_id
      )
    ORDER BY m_count DESC, c.created_at DESC
    LIMIT 20;
END;


-- Function: get_campus_classes

DECLARE
    v_uni_id uuid;
    v_dept text;
BEGIN
    SELECT university_id, department INTO v_uni_id, v_dept
    FROM public.profiles WHERE id = req_user_id;

    RETURN QUERY
    SELECT 
        c.id AS conversation_id,
        c.title::text AS title,
        COALESCE(c.metadata, '{}'::jsonb) AS metadata,
        (SELECT COUNT(*) FROM public.conversation_members cm WHERE cm.conversation_id = c.id)::integer AS member_count,
        p.full_name AS owner_name,
        p.avatar_url AS owner_avatar,
        (
            CASE 
                WHEN EXISTS (
                    SELECT 1 FROM conversation_members cm1
                    JOIN conversation_members cm2 ON cm1.conversation_id = cm2.conversation_id
                    JOIN conversations dm ON cm1.conversation_id = dm.id
                    WHERE dm.type = 'dm' AND cm1.user_id = req_user_id AND cm2.user_id = c.owner_id
                ) THEN 10 ELSE 0 
            END
            +
            CASE WHEN p.department = v_dept THEN 5 ELSE 0 END
        )::integer AS relevance_score,
        EXISTS (SELECT 1 FROM public.conversation_members cm WHERE cm.conversation_id = c.id AND cm.user_id = req_user_id) AS is_member
    FROM public.conversations c
    JOIN public.profiles p ON c.owner_id = p.id
    WHERE c.type = 'group'
      AND c.metadata->>'focus' = 'Class'
      AND (c.metadata->>'privacy' = 'public' OR c.metadata->>'privacy' IS NULL)
      AND p.university_id = v_uni_id
    ORDER BY relevance_score DESC, member_count DESC, c.created_at DESC;
END;


-- Function: get_personal_observatory_data

DECLARE
    v_user_id UUID := COALESCE(p_user_id, auth.uid());
    v_current_streak INT := 0;
    v_longest_streak INT := 0;
    v_unique_correct INT := 0;
    v_topics_mastered INT := 0;
    v_srs_graduated INT := 0;
    v_study_mins INT := 0;
    v_raw_score NUMERIC := 0;
    v_mastery_rating INT := 0;
    v_division TEXT := 'Division V';
    v_my_name TEXT;
    v_my_avatar TEXT;
    v_is_pro BOOLEAN := false;
    v_show_on_leaderboard BOOLEAN := true;
    v_my_rank INT := 1;
    v_total_scholars INT := 0;
    v_leaderboard jsonb := '[]'::jsonb;
    v_weekly jsonb := '[]'::jsonb;
    v_user_in_top10 BOOLEAN := false;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('error', 'Unauthenticated');
    END IF;

    SELECT 
        COALESCE(current_streak, 0),
        COALESCE(longest_streak, 0),
        COALESCE(full_name, 'Student'),
        avatar_url,
        COALESCE(is_pro, false),
        COALESCE(show_on_leaderboard, true)
    INTO v_current_streak, v_longest_streak, v_my_name, v_my_avatar, v_is_pro, v_show_on_leaderboard
    FROM public.profiles
    WHERE id = v_user_id;

    SELECT COUNT(DISTINCT question_id)
    INTO v_unique_correct
    FROM public.user_question_attempts
    WHERE user_id = v_user_id AND is_correct = true AND question_id IS NOT NULL;

    SELECT COUNT(*)
    INTO v_topics_mastered
    FROM (
        SELECT course_code, topic_tag
        FROM public.user_question_attempts
        WHERE user_id = v_user_id
        GROUP BY course_code, topic_tag
        HAVING COUNT(*) >= 3 
           AND (COUNT(*) FILTER (WHERE is_correct = true)::float / COUNT(*)::float) >= 0.75
    ) mastered;

    SELECT COUNT(*)
    INTO v_srs_graduated
    FROM public.user_flashcard_srs
    WHERE user_id = v_user_id AND state = 'review' AND interval_days >= 3.0;

    SELECT COALESCE(SUM(LEAST(total_active_seconds, 14400)), 0) / 60
    INTO v_study_mins
    FROM public.user_daily_telemetry
    WHERE user_id = v_user_id;

    v_raw_score := (v_unique_correct * 10) + 
                   (v_topics_mastered * 50) + 
                   (v_srs_graduated * 15) + 
                   (v_study_mins * 0.25) + 
                   (LEAST(v_current_streak, 60) * 15);

    v_mastery_rating := ROUND(GREATEST(0, LEAST(5000, v_raw_score)));

    IF v_mastery_rating >= 3500 THEN
        v_division := 'Division I';
    ELSIF v_mastery_rating >= 2500 THEN
        v_division := 'Division II';
    ELSIF v_mastery_rating >= 1500 THEN
        v_division := 'Division III';
    ELSIF v_mastery_rating >= 600 THEN
        v_division := 'Division IV';
    ELSE
        v_division := 'Division V';
    END IF;

    UPDATE public.profiles
    SET level = v_division
    WHERE id = v_user_id AND level IS DISTINCT FROM v_division;

    WITH scholar_stats AS (
        SELECT 
            p.id,
            p.full_name,
            p.avatar_url,
            COALESCE(p.is_pro, false) AS is_pro,
            COALESCE(p.show_on_leaderboard, true) AS is_visible,
            ROUND(GREATEST(0, LEAST(5000, 
                (COALESCE(q.unique_correct, 0) * 10) +
                (COALESCE(tm.mastered_count, 0) * 50) +
                (COALESCE(srs.graduated_count, 0) * 15) +
                (COALESCE(tel.study_mins, 0) * 0.25) +
                (LEAST(COALESCE(p.current_streak, 0), 60) * 15)
            )))::int AS mr
        FROM public.profiles p
        LEFT JOIN (
            SELECT user_id, COUNT(DISTINCT question_id) AS unique_correct
            FROM public.user_question_attempts
            WHERE is_correct = true AND question_id IS NOT NULL
            GROUP BY user_id
        ) q ON q.user_id = p.id
        LEFT JOIN (
            SELECT user_id, COUNT(*) AS mastered_count
            FROM (
                SELECT user_id, course_code, topic_tag
                FROM public.user_question_attempts
                GROUP BY user_id, course_code, topic_tag
                HAVING COUNT(*) >= 3 AND (COUNT(*) FILTER (WHERE is_correct = true)::float / COUNT(*)::float) >= 0.75
            ) t GROUP BY user_id
        ) tm ON tm.user_id = p.id
        LEFT JOIN (
            SELECT user_id, COUNT(*) AS graduated_count
            FROM public.user_flashcard_srs
            WHERE state = 'review' AND interval_days >= 3.0
            GROUP BY user_id
        ) srs ON srs.user_id = p.id
        LEFT JOIN (
            SELECT user_id, SUM(LEAST(total_active_seconds, 14400)) / 60 AS study_mins
            FROM public.user_daily_telemetry
            GROUP BY user_id
        ) tel ON tel.user_id = p.id
        WHERE p.show_on_leaderboard = true OR p.id = v_user_id
    ),
    ranked AS (
        SELECT 
            s.id,
            s.full_name,
            s.avatar_url,
            s.is_pro,
            s.mr,
            s.is_visible,
            CASE 
                WHEN s.mr >= 3500 THEN 'Division I'
                WHEN s.mr >= 2500 THEN 'Division II'
                WHEN s.mr >= 1500 THEN 'Division III'
                WHEN s.mr >= 600 THEN 'Division IV'
                ELSE 'Division V'
            END AS div,
            DENSE_RANK() OVER (ORDER BY s.mr DESC, s.id ASC)::int AS rk
        FROM scholar_stats s
    )
    SELECT 
        COALESCE(jsonb_agg(
            jsonb_build_object(
                'rank', rk,
                'id', id,
                'name', full_name,
                'avatar_url', avatar_url,
                'is_pro', is_pro,
                'mastery_rating', mr,
                'division', div,
                'is_user', (id = v_user_id)
            ) ORDER BY rk ASC
        ) FILTER (WHERE rk <= 10 AND is_visible = true), '[]'::jsonb),
        COALESCE(MAX(rk) FILTER (WHERE id = v_user_id), 1),
        COUNT(*)::int
    INTO v_leaderboard, v_my_rank, v_total_scholars
    FROM ranked;

    SELECT EXISTS(
        SELECT 1 FROM jsonb_array_elements(v_leaderboard) elem 
        WHERE (elem->>'id')::uuid = v_user_id
    ) INTO v_user_in_top10;

    WITH week_days AS (
        SELECT 
            generate_series(
                date_trunc('week', CURRENT_DATE)::date,
                (date_trunc('week', CURRENT_DATE) + INTERVAL '6 days')::date,
                INTERVAL '1 day'
            )::date AS d
    )
    SELECT jsonb_agg(
        jsonb_build_object(
            'day', to_char(wd.d, 'Dy'),
            'date', wd.d,
            'active_seconds', COALESCE(t.total_active_seconds, 0),
            'hours', ROUND((COALESCE(t.total_active_seconds, 0)::numeric / 3600.0), 1),
            'is_today', (wd.d = CURRENT_DATE)
        ) ORDER BY wd.d ASC
    )
    INTO v_weekly
    FROM week_days wd
    LEFT JOIN public.user_daily_telemetry t ON t.user_id = v_user_id AND t.date = wd.d;

    RETURN jsonb_build_object(
        'mastery_rating', v_mastery_rating,
        'division', v_division,
        'current_streak', v_current_streak,
        'longest_streak', v_longest_streak,
        'study_hours', ROUND((v_study_mins::numeric / 60.0), 1),
        'my_standing', jsonb_build_object(
            'rank', v_my_rank,
            'name', v_my_name,
            'avatar_url', v_my_avatar,
            'is_pro', v_is_pro,
            'mastery_rating', v_mastery_rating,
            'division', v_division,
            'total_scholars', v_total_scholars,
            'is_top10', v_user_in_top10,
            'is_hidden', (NOT v_show_on_leaderboard)
        ),
        'leaderboard', v_leaderboard,
        'weekly_velocity', COALESCE(v_weekly, '[]'::jsonb)
    );
END;


-- Function: get_peer_questions

BEGIN
    RETURN QUERY
    SELECT pq.id, pq.title, pq.body, pq.course_tag, pq.created_at,
           pq.user_id AS asker_id, p.full_name AS asker_name, p.avatar_url AS asker_avatar,
           COALESCE(p.is_pro, false) AS asker_is_pro,
           pq.replies_count
    FROM public.peer_questions pq
    JOIN public.profiles p ON p.id = pq.user_id
    ORDER BY pq.created_at DESC
    LIMIT 50;
END;


-- Function: get_featured_events

BEGIN
    RETURN QUERY 
    SELECT fe.id, fe.title, fe.body, fe.image_url, fe.tag_text, fe.tag_color, 
           fe.button_text, fe.button_color, fe.action_type, fe.html_content, 
           fe.external_url, fe.app_route, fe.metadata, fe.created_at,
           COALESCE(fe.weight, 10)::integer as weight
    FROM public.featured_events fe
    WHERE fe.is_active = true
    ORDER BY COALESCE(fe.weight, 10) DESC, fe.created_at DESC;
END;


-- Function: get_user_weaknesses

BEGIN
    RETURN QUERY
    SELECT 
        uqa.course_code,
        uqa.topic_tag,
        COUNT(*) AS total_attempts,
        COUNT(*) FILTER (WHERE uqa.is_correct = true) AS correct_attempts,
        ROUND((COUNT(*) FILTER (WHERE uqa.is_correct = true) * 100.0) / COUNT(*), 2) AS accuracy_percentage
    FROM public.user_question_attempts uqa
    WHERE uqa.user_id = p_user_id
      AND uqa.attempted_at >= NOW() - INTERVAL '30 days'
    GROUP BY uqa.course_code, uqa.topic_tag
    ORDER BY accuracy_percentage ASC, total_attempts DESC;
END;


-- Function: record_telemetry_flush

DECLARE
    v_user_id UUID := auth.uid();
    v_date DATE := CURRENT_DATE;
    v_dur INT := GREATEST(p_duration_seconds, 0);
    v_act INT := GREATEST(p_interactions, 0);
BEGIN
    IF v_user_id IS NULL THEN
        RETURN;
    END IF;

    INSERT INTO public.user_daily_telemetry (
        user_id, date, total_active_seconds,
        time_exam_seconds, time_books_seconds, time_miron_seconds,
        time_connect_seconds, time_discover_seconds, time_home_seconds,
        total_interactions, updated_at
    ) VALUES (
        v_user_id, v_date, v_dur,
        CASE WHEN p_feature = 'exam' THEN v_dur ELSE 0 END,
        CASE WHEN p_feature = 'books' THEN v_dur ELSE 0 END,
        CASE WHEN p_feature = 'miron' THEN v_dur ELSE 0 END,
        CASE WHEN p_feature = 'connect' THEN v_dur ELSE 0 END,
        CASE WHEN p_feature = 'discover' THEN v_dur ELSE 0 END,
        CASE WHEN p_feature = 'home' THEN v_dur ELSE 0 END,
        v_act, NOW()
    )
    ON CONFLICT (user_id, date) DO UPDATE SET
        total_active_seconds = user_daily_telemetry.total_active_seconds + EXCLUDED.total_active_seconds,
        time_exam_seconds = user_daily_telemetry.time_exam_seconds + EXCLUDED.time_exam_seconds,
        time_books_seconds = user_daily_telemetry.time_books_seconds + EXCLUDED.time_books_seconds,
        time_miron_seconds = user_daily_telemetry.time_miron_seconds + EXCLUDED.time_miron_seconds,
        time_connect_seconds = user_daily_telemetry.time_connect_seconds + EXCLUDED.time_connect_seconds,
        time_discover_seconds = user_daily_telemetry.time_discover_seconds + EXCLUDED.time_discover_seconds,
        time_home_seconds = user_daily_telemetry.time_home_seconds + EXCLUDED.time_home_seconds,
        total_interactions = user_daily_telemetry.total_interactions + EXCLUDED.total_interactions,
        updated_at = NOW();
END;


-- Function: get_public_profiles

BEGIN
    RETURN QUERY
    SELECT 
        p.id, 
        p.full_name, 
        p.avatar_url, 
        p.username, 
        p.department, 
        p.level, 
        COALESCE(p.is_pro, false) AS is_pro
    FROM public.profiles p
    WHERE p.id = ANY(user_ids);
END;


-- Function: get_user_profile_public

DECLARE
    res jsonb;
BEGIN
    SELECT jsonb_build_object(
        'id', p.id,
        'full_name', p.full_name,
        'username', p.username,
        'avatar_url', p.avatar_url,
        'department', p.department,
        'level', p.level,
        'bio', p.bio,
        'is_pro', COALESCE(p.is_pro, false)
    ) INTO res
    FROM public.profiles p
    WHERE p.id = target_user_id;
    
    RETURN res;
END;


-- Function: get_and_lock_pending_pages

DECLARE
    target_ids UUID[];
BEGIN
    -- Select pending IDs where pdf_name matches and page_number > page_offset, locking them
    SELECT array_agg(id) INTO target_ids
    FROM (
        SELECT id 
        FROM public.book_progress 
        WHERE status = 'pending' 
          AND pdf_name = target_pdf_name
          AND page_number > page_offset
        ORDER BY page_number ASC 
        LIMIT limit_count
        FOR UPDATE SKIP LOCKED
    ) sub;

    IF target_ids IS NOT NULL THEN
        -- Update state to processing
        UPDATE public.book_progress
        SET status = 'processing', updated_at = now()
        WHERE id = ANY(target_ids);

        RETURN QUERY 
        SELECT page_number 
        FROM public.book_progress 
        WHERE id = ANY(target_ids)
        ORDER BY page_number ASC;
    END IF;
END;


-- Function: get_and_lock_asset_uploads

BEGIN
    RETURN QUERY
    WITH candidate_items AS (
        SELECT id, file_path
        FROM public.asset_upload_queue
        WHERE zip_name = p_zip_name
          AND status = 'pending'
        ORDER BY id ASC
        LIMIT p_limit
        FOR UPDATE SKIP LOCKED
    )
    UPDATE public.asset_upload_queue q
    SET 
        status = 'processing',
        updated_at = timezone('utc'::text, now())
    FROM candidate_items c
    WHERE q.id = c.id
    RETURNING q.id AS locked_id, q.file_path AS locked_file_path;
END;


-- Function: get_compiled_book

DECLARE
    v_prefix text;
    v_result jsonb;
BEGIN
    -- Construct target public bucket prefix (e.g. 'https://xyz.supabase.co/storage/v1/object/public/book-assets/Physics.pdf/')
    IF base_storage_url IS NOT NULL AND base_storage_url <> '' THEN
        v_prefix := rtrim(base_storage_url, '/') || '/' || target_pdf || '/';
    ELSE
        v_prefix := '/storage/v1/object/public/book-assets/' || target_pdf || '/';
    END IF;

    SELECT coalesce(
        jsonb_object_agg(
            kv.key,
            kv.value
        ),
        '{}'::jsonb
    )
    INTO v_result
    FROM (
        SELECT 
            -- Fast C-level replacement of 'assets/' placeholder with the public storage URL
            replace(result_json::text, 'assets/', v_prefix)::jsonb AS result_json
        FROM public.book_results
        WHERE pdf_name = target_pdf
        ORDER BY page_number ASC
    ) sub,
    LATERAL jsonb_each(sub.result_json) kv;

    RETURN v_result;
END;


-- Function: get_book_reader_payload

DECLARE
    v_book record;
    v_pages jsonb;
BEGIN
    -- 1. Fetch Book Metadata
    SELECT id, title, course_code, toc, page_offset, custom_css
    INTO v_book
    FROM public.books
    WHERE id = p_book_id;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('error', 'Book not found');
    END IF;

    -- 2. Aggregate pages directly without naive string corruption
    SELECT coalesce(
        jsonb_agg(
            jsonb_build_object(
                'id', bp.id,
                'page_number', bp.page_number,
                'page_key', bp.page_key,
                'manual_flag', bp.manual_flag,
                'content_json', bp.content_json
            )
            ORDER BY bp.page_number ASC
        ),
        '[]'::jsonb
    )
    INTO v_pages
    FROM public.book_pages bp
    WHERE bp.book_id = p_book_id;

    -- 3. Return clean atomic payload
    RETURN jsonb_build_object(
        'book_id', v_book.id,
        'title', v_book.title,
        'course_code', v_book.course_code,
        'toc', coalesce(v_book.toc, '[]'::jsonb),
        'page_offset', coalesce(v_book.page_offset, 0),
        'custom_css', v_book.custom_css,
        'pages', v_pages
    );
END;


-- Function: record_telemetry_flush

DECLARE
    v_user_id UUID := auth.uid();
    v_date DATE := CURRENT_DATE;
    v_dur INT := GREATEST(p_duration_seconds, 0);
    v_act INT := GREATEST(p_interactions, 0);
    
    v_book_id UUID;
    v_course_code TEXT;
    v_current_page INT;
    v_chapter_title TEXT;
    v_section_title TEXT;
    v_total_pages INT;
    v_completion_pct NUMERIC(5, 2) := 0.00;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'reason', 'unauthenticated');
    END IF;

    -- 1. Daily Roll-up Telemetry update
    INSERT INTO public.user_daily_telemetry (
        user_id, date, total_active_seconds,
        time_exam_seconds, time_books_seconds, time_miron_seconds,
        time_connect_seconds, time_discover_seconds, time_home_seconds,
        total_interactions, updated_at
    ) VALUES (
        v_user_id, v_date, v_dur,
        CASE WHEN p_feature = 'exam' THEN v_dur ELSE 0 END,
        CASE WHEN p_feature = 'books' THEN v_dur ELSE 0 END,
        CASE WHEN p_feature = 'miron' THEN v_dur ELSE 0 END,
        CASE WHEN p_feature = 'connect' THEN v_dur ELSE 0 END,
        CASE WHEN p_feature = 'discover' THEN v_dur ELSE 0 END,
        CASE WHEN p_feature = 'home' THEN v_dur ELSE 0 END,
        v_act, NOW()
    )
    ON CONFLICT (user_id, date) DO UPDATE SET
        total_active_seconds = user_daily_telemetry.total_active_seconds + EXCLUDED.total_active_seconds,
        time_exam_seconds = user_daily_telemetry.time_exam_seconds + EXCLUDED.time_exam_seconds,
        time_books_seconds = user_daily_telemetry.time_books_seconds + EXCLUDED.time_books_seconds,
        time_miron_seconds = user_daily_telemetry.time_miron_seconds + EXCLUDED.time_miron_seconds,
        time_connect_seconds = user_daily_telemetry.time_connect_seconds + EXCLUDED.time_connect_seconds,
        time_discover_seconds = user_daily_telemetry.time_discover_seconds + EXCLUDED.time_discover_seconds,
        time_home_seconds = user_daily_telemetry.time_home_seconds + EXCLUDED.time_home_seconds,
        total_interactions = user_daily_telemetry.total_interactions + EXCLUDED.total_interactions,
        updated_at = NOW();

    -- 2. Upsert Course Position & Progress Ledger if book context is provided
    IF p_book_context IS NOT NULL AND (p_book_context->>'book_id') IS NOT NULL THEN
        v_book_id := (p_book_context->>'book_id')::UUID;
        v_course_code := p_book_context->>'course_code';
        v_current_page := COALESCE((p_book_context->>'current_page')::INT, 1);
        v_chapter_title := p_book_context->>'chapter_title';
        v_section_title := p_book_context->>'section_title';
        v_total_pages := COALESCE((p_book_context->>'total_pages')::INT, 1);

        IF v_total_pages > 0 THEN
            v_completion_pct := ROUND(LEAST(100.0, (v_current_page::NUMERIC / v_total_pages::NUMERIC) * 100.0), 2);
        END IF;

        INSERT INTO public.user_course_progress (
            user_id, book_id, course_code,
            current_page, furthest_page,
            chapter_title, section_title,
            reading_seconds, completion_pct,
            last_read_at, updated_at
        ) VALUES (
            v_user_id, v_book_id, v_course_code,
            v_current_page, v_current_page,
            v_chapter_title, v_section_title,
            v_dur, v_completion_pct,
            NOW(), NOW()
        )
        ON CONFLICT (user_id, book_id) DO UPDATE SET
            course_code = COALESCE(EXCLUDED.course_code, user_course_progress.course_code),
            current_page = EXCLUDED.current_page,
            furthest_page = GREATEST(user_course_progress.furthest_page, EXCLUDED.current_page),
            chapter_title = COALESCE(EXCLUDED.chapter_title, user_course_progress.chapter_title),
            section_title = COALESCE(EXCLUDED.section_title, user_course_progress.section_title),
            reading_seconds = user_course_progress.reading_seconds + v_dur,
            completion_pct = GREATEST(user_course_progress.completion_pct, EXCLUDED.completion_pct),
            last_read_at = NOW(),
            updated_at = NOW();
    END IF;

    RETURN jsonb_build_object('success', true);
END;


-- Function: get_user_course_positions

BEGIN
    RETURN QUERY
    SELECT 
        ucp.book_id,
        b.title AS book_title,
        COALESCE(ucp.course_code, b.course_code) AS course_code,
        ucp.current_page,
        ucp.furthest_page,
        ucp.chapter_title,
        ucp.section_title,
        ucp.reading_seconds,
        ucp.completion_pct,
        ucp.last_read_at
    FROM public.user_course_progress ucp
    JOIN public.books b ON ucp.book_id = b.id
    WHERE ucp.user_id = p_user_id
    ORDER BY ucp.last_read_at DESC;
END;


-- Function: handle_payment_approval_trigger

DECLARE
    v_duration INTERVAL;
    v_expires_at TIMESTAMPTZ;
BEGIN
    IF NEW.status = 'approved' AND (OLD.status IS DISTINCT FROM 'approved') THEN
        IF NEW.plan = 'semester' THEN
            v_duration := INTERVAL '6 months';
        ELSE
            v_duration := INTERVAL '1 year';
        END IF;

        v_expires_at := now() + v_duration;

        -- Activate Gold Pass on profile
        UPDATE public.profiles
        SET is_pro = true,
            pro_expires_at = v_expires_at,
            updated_at = now()
        WHERE id = NEW.user_id;

        -- Dispatch notification to the user
        INSERT INTO public.notifications (
            user_id,
            type,
            title,
            description,
            icon,
            action_data
        ) VALUES (
            NEW.user_id,
            'reward',
            'LinkUp Gold Activated! 👑',
            'Your payment has been verified. Welcome to LinkUp Gold Pass with full access to Miron and Exam archives!',
            'fa-crown',
            jsonb_build_object('action', 'open_profile')
        );
    END IF;

    RETURN NEW;
END;


-- Function: get_payment_methods

DECLARE
    v_val JSONB;
BEGIN
    SELECT value INTO v_val FROM public.system_config WHERE key = 'payment_methods';
    RETURN COALESCE(v_val, '{}'::jsonb);
END;


-- Function: get_user_conversations

BEGIN
  RETURN QUERY
  SELECT 
    c.id as conversation_id,
    c.type::text,
    c.title,
    c.avatar_url,
    COALESCE(c.last_message_at, c.created_at) as last_message_at,
    (
        SELECT CASE 
                 WHEN COALESCE(m.text, '') != '' THEN m.text 
                 WHEN m.attachments IS NOT NULL AND jsonb_typeof(m.attachments) = 'array' AND jsonb_array_length(m.attachments) > 0 THEN
                   CASE 
                     WHEN m.attachments->0->>'type' = 'poll' THEN '📊 Poll' 
                     WHEN m.attachments->0->>'type' LIKE 'image/%' THEN '📷 Photo'
                     WHEN m.attachments->0->>'type' LIKE 'video/%' THEN '🎥 Video'
                     WHEN m.attachments->0->>'type' LIKE 'audio/%' THEN '🎵 Audio'
                     ELSE '📎 ' || COALESCE(m.attachments->0->>'name', 'File')
                   END
                 ELSE '' 
               END 
        FROM public.messages m 
        WHERE m.conversation_id = c.id 
        ORDER BY m.created_at DESC 
        LIMIT 1
    ) as last_message_text,
    (
        SELECT count(*) 
        FROM public.messages m2 
        WHERE m2.conversation_id = c.id 
          AND m2.sender_id != req_user_id 
          AND m2.created_at > cm.last_read_at
    ) as unread_count,
    (
        SELECT p.full_name 
        FROM public.conversation_members cm2 
        JOIN public.profiles p ON p.id = cm2.user_id 
        WHERE cm2.conversation_id = c.id AND cm2.user_id != req_user_id 
        LIMIT 1
    ) as other_user_name,
    (
        SELECT p.avatar_url 
        FROM public.conversation_members cm2 
        JOIN public.profiles p ON p.id = cm2.user_id 
        WHERE cm2.conversation_id = c.id AND cm2.user_id != req_user_id 
        LIMIT 1
    ) as other_user_avatar,
    (
        SELECT p.id 
        FROM public.conversation_members cm2 
        JOIN public.profiles p ON p.id = cm2.user_id 
        WHERE cm2.conversation_id = c.id AND cm2.user_id != req_user_id 
        LIMIT 1
    ) as other_user_id,
    (
        SELECT p.last_seen_at 
        FROM public.conversation_members cm2 
        JOIN public.profiles p ON p.id = cm2.user_id 
        WHERE cm2.conversation_id = c.id AND cm2.user_id != req_user_id 
        LIMIT 1
    ) as other_user_last_seen,
    COALESCE(c.metadata, '{}'::jsonb) as metadata
  FROM public.conversations c
  JOIN public.conversation_members cm ON c.id = cm.conversation_id
  WHERE cm.user_id = req_user_id
  ORDER BY COALESCE(c.last_message_at, c.created_at) DESC NULLS LAST;
END;


-- Function: get_my_active_devices

DECLARE
    v_res JSONB;
BEGIN
    SELECT jsonb_agg(jsonb_build_object(
        'device_type', device_type,
        'device_name', device_name,
        'is_primary', is_primary,
        'last_active_at', last_active_at
    ) ORDER BY is_primary DESC, last_active_at DESC)
    INTO v_res
    FROM public.user_device_seats
    WHERE user_id = auth.uid();

    RETURN COALESCE(v_res, '[]'::jsonb);
END;


-- Function: claim_device_lease

DECLARE
    v_user_id UUID := auth.uid();
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', false);
    END IF;

    UPDATE public.user_device_seats 
    SET last_lease_at = now()
    WHERE user_id = v_user_id AND device_id = p_device_id;

    RETURN jsonb_build_object('success', true, 'claimed_at', now());
END;


-- Function: sync_device_session

DECLARE
    v_user_id UUID := auth.uid();
    v_existing_slot RECORD;
    v_is_primary BOOLEAN := false;
    v_device_count INT;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Unauthenticated session sync';
    END IF;

    -- 1. Check existing seat for this device category (mobile or desktop)
    SELECT * INTO v_existing_slot 
    FROM public.user_device_seats 
    WHERE user_id = v_user_id AND device_type = p_device_type;

    -- CASE A: First time registering this device category
    IF NOT FOUND THEN
        SELECT count(*) INTO v_device_count 
        FROM public.user_device_seats 
        WHERE user_id = v_user_id;

        -- Mobile Anchor Priority: Mobile always takes primary if first device or if upgrading from PC
        IF p_device_type = 'mobile' THEN
            v_is_primary := true;
            -- Demote any previous desktop primary to companion
            UPDATE public.user_device_seats 
            SET is_primary = false 
            WHERE user_id = v_user_id AND device_type = 'desktop';
        ELSIF v_device_count = 0 THEN
            -- First device ever is desktop, temporarily primary until mobile arrives
            v_is_primary := true;
        END IF;

        INSERT INTO public.user_device_seats (
            user_id, device_id, device_type, device_name, is_primary, last_active_at, last_lease_at
        ) VALUES (
            v_user_id, p_device_id, p_device_type, p_device_name, v_is_primary, now(), now()
        );

        RETURN jsonb_build_object(
            'status', 'registered',
            'device_type', p_device_type,
            'is_primary', v_is_primary
        );
    END IF;

    -- CASE B: Same physical device returning (Normal Session Refresh)
    IF v_existing_slot.device_id = p_device_id THEN
        IF p_device_type = 'mobile' AND NOT v_existing_slot.is_primary THEN
            v_is_primary := true;
            UPDATE public.user_device_seats SET is_primary = false WHERE user_id = v_user_id AND device_type = 'desktop';
        ELSE
            v_is_primary := v_existing_slot.is_primary;
        END IF;

        UPDATE public.user_device_seats 
        SET last_active_at = now(),
            device_name = p_device_name,
            is_primary = v_is_primary
        WHERE user_id = v_user_id AND device_type = p_device_type;

        RETURN jsonb_build_object(
            'status', 'active',
            'device_type', p_device_type,
            'is_primary', v_is_primary
        );
    END IF;

    -- CASE C: Different device of the same type colliding
    IF p_device_type = 'desktop' THEN
        -- Ephemeral Companion Policy: Desktop auto-replaces the old PC seamlessly!
        UPDATE public.user_device_seats 
        SET device_id = p_device_id,
            device_name = p_device_name,
            last_active_at = now(),
            last_lease_at = now()
        WHERE user_id = v_user_id AND device_type = 'desktop';

        RETURN jsonb_build_object(
            'status', 'replaced_secondary',
            'device_type', 'desktop',
            'replaced_device_name', v_existing_slot.device_name
        );
    ELSE
        -- Mobile Collision: Primary Anchor requires confirmation unless forced
        IF NOT p_force_transfer THEN
            RETURN jsonb_build_object(
                'status', 'requires_primary_confirmation',
                'current_primary_name', v_existing_slot.device_name
            );
        ELSE
            -- User confirmed switch to new primary phone
            UPDATE public.user_device_seats 
            SET device_id = p_device_id,
                device_name = p_device_name,
                is_primary = true,
                last_active_at = now(),
                last_lease_at = now()
            WHERE user_id = v_user_id AND device_type = 'mobile';

            UPDATE public.user_device_seats SET is_primary = false WHERE user_id = v_user_id AND device_type = 'desktop';

            RETURN jsonb_build_object(
                'status', 'transferred_primary',
                'device_type', 'mobile'
            );
        END IF;
    END IF;
END;


-- Function: heartbeat_device_lease

DECLARE
    v_user_id UUID := auth.uid();
    v_my_seat RECORD;
    v_other_seat RECORD;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('status', 'unauthenticated');
    END IF;

    -- 1. Verify this device is still officially seated
    SELECT * INTO v_my_seat 
    FROM public.user_device_seats 
    WHERE user_id = v_user_id AND device_id = p_device_id;

    IF NOT FOUND THEN
        -- Device was replaced or never registered
        RETURN jsonb_build_object('status', 'evicted');
    END IF;

    -- 2. Update active presence timestamp
    UPDATE public.user_device_seats 
    SET last_active_at = now()
    WHERE id = v_my_seat.id;

    -- 3. Check if the other device holds an active exclusive lease (< 45s old)
    SELECT device_name, device_type, last_lease_at INTO v_other_seat
    FROM public.user_device_seats
    WHERE user_id = v_user_id 
      AND device_id != p_device_id
      AND last_lease_at > (now() - INTERVAL '45 seconds');

    IF FOUND THEN
        RETURN jsonb_build_object(
            'status', 'lease_conflict',
            'holder_name', v_other_seat.device_name,
            'holder_type', v_other_seat.device_type
        );
    END IF;

    RETURN jsonb_build_object('status', 'ok');
END;


-- Function: user_has_password

  SELECT COALESCE((encrypted_password IS NOT NULL AND encrypted_password != ''), false)
  FROM auth.users
  WHERE id = auth.uid();


-- Function: get_flashcard_deck_stats

DECLARE
    v_user_id UUID := auth.uid();
    v_mistakes_due INT := 0;
    v_decks jsonb;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('mistakes_due', 0, 'course_decks', '[]'::jsonb);
    END IF;

    -- Mistakes count (Exam mistakes + custom cards marked mistake)
    SELECT COUNT(*)
    INTO v_mistakes_due
    FROM (
        SELECT umf.id, srs.card_id, srs.next_review_at
        FROM public.user_mistake_flashcards umf
        LEFT JOIN public.user_flashcard_srs srs ON srs.card_id = umf.id AND srs.user_id = v_user_id
        WHERE umf.user_id = v_user_id
        UNION ALL
        SELECT ucf.id, srs.card_id, srs.next_review_at
        FROM public.user_custom_flashcards ucf
        LEFT JOIN public.user_flashcard_srs srs ON srs.card_id = ucf.id AND srs.user_id = v_user_id
        WHERE ucf.user_id = v_user_id AND ucf.is_mistake = true
    ) m
    WHERE m.card_id IS NULL OR m.next_review_at <= NOW();

    -- Course Decks totals and due counts (Public + Private Miron cards)
    SELECT jsonb_agg(d)
    INTO v_decks
    FROM (
        SELECT 
            all_cards.course_code,
            COALESCE(b.title, all_cards.course_code) as title,
            COUNT(all_cards.id) as total_cards,
            COUNT(all_cards.id) FILTER (WHERE all_cards.srs_card_id IS NULL OR all_cards.next_review_at <= NOW()) as due_cards
        FROM (
            SELECT cf.id, cf.course_code, srs.card_id as srs_card_id, srs.next_review_at
            FROM public.course_flashcards cf
            LEFT JOIN public.user_flashcard_srs srs ON srs.card_id = cf.id AND srs.user_id = v_user_id
            UNION ALL
            SELECT ucf.id, ucf.course_code, srs.card_id as srs_card_id, srs.next_review_at
            FROM public.user_custom_flashcards ucf
            LEFT JOIN public.user_flashcard_srs srs ON srs.card_id = ucf.id AND srs.user_id = v_user_id
            WHERE ucf.user_id = v_user_id
        ) all_cards
        LEFT JOIN public.books b ON b.course_code = all_cards.course_code
        GROUP BY all_cards.course_code, b.title
        ORDER BY all_cards.course_code ASC
    ) d;

    RETURN jsonb_build_object(
        'mistakes_due', v_mistakes_due,
        'course_decks', COALESCE(v_decks, '[]'::jsonb)
    );
END;


-- Function: record_flashcard_review

DECLARE
    v_user_id UUID := auth.uid();
    v_next_review TIMESTAMPTZ;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('error', 'Unauthenticated');
    END IF;

    -- Clean intervals: Hard -> 1 day, Good -> 3 days, Easy -> 7 days
    IF p_difficulty = 'hard' THEN
        v_next_review := now() + INTERVAL '1 day';
    ELSIF p_difficulty = 'good' THEN
        v_next_review := now() + INTERVAL '3 days';
    ELSE
        v_next_review := now() + INTERVAL '7 days';
    END IF;

    INSERT INTO public.user_flashcard_reviews (
        user_id, card_id, card_type, difficulty, review_count, next_review_at, last_reviewed_at
    ) VALUES (
        v_user_id, p_card_id, p_card_type, p_difficulty, 1, v_next_review, now()
    )
    ON CONFLICT (user_id, card_id, card_type) DO UPDATE SET
        difficulty = EXCLUDED.difficulty,
        review_count = user_flashcard_reviews.review_count + 1,
        next_review_at = EXCLUDED.next_review_at,
        last_reviewed_at = now();

    RETURN jsonb_build_object('success', true, 'next_review_at', v_next_review);
END;


