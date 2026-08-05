-- Carries per-field provenance across the review-gated metadata path.
--
-- V176 records which provider filled each field, but only on the paths where the merger's result goes
-- straight into the updater. With reviewBeforeApply the merged result is parked in a proposal and
-- accepted later by the *client*, which replays it through the ordinary metadata PUT — a request the
-- server cannot tell apart from a user typing into the editor. So the accept arrives with no provider
-- information at all, and the fields it changes lose the rows they had.
--
-- The map has to survive the wait, and metadata_json cannot carry it: BookMetadata.fieldProviders is
-- @JsonIgnore, which is what stops a client asserting provenance through that same PUT endpoint. Hence
-- a column of its own, written by the server and never echoed to any API response.
--
-- What is stored is not the merger's raw map but the subset of it whose proposed value *differs* from
-- what the book held when the proposal was built. That is the same rule the direct path enforces
-- through the updater's WRITTEN outcome: a provider that merely agreed with a value already present
-- earns no attribution, because agreement cannot be told apart from the user having typed what the
-- provider would have said.

ALTER TABLE metadata_fetch_proposals
    ADD COLUMN IF NOT EXISTS field_providers_json JSON NULL;
