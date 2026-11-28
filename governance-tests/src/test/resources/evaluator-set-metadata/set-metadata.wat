;; Evaluator-action component: every evaluation calls response.set-metadata(METADATA_KEY, "evaluator-value").
;; The test replaces METADATA_KEY and KEY_LENGTH before it registers the evaluator.
(component
  (import "wanaku:evaluator/response" (instance $response
    (export "set-metadata" (func (param "key" string) (param "value" string)))))
  (alias export $response "set-metadata" (func $set-metadata))
  (core module $strings
    (memory (export "memory") 1)
    (data (i32.const 0) "METADATA_KEY")
    (data (i32.const 512) "evaluator-value"))
  (core instance $strings-instance (instantiate $strings))
  (alias core export $strings-instance "memory" (core memory $strings-memory))
  (core func $set-metadata-lowered (canon lower (func $set-metadata) (memory $strings-memory)))
  (core instance $host (export "set-metadata" (func $set-metadata-lowered)))
  (core module $processor
    (import "host" "set-metadata" (func $set-metadata (param i32 i32 i32 i32)))
    (memory (export "memory") 1)
    (global $next (mut i32) (i32.const 1024))
    (func (export "realloc") (param i32 i32 i32) (param $size i32) (result i32)
      (local $pointer i32)
      (local.set $pointer (global.get $next))
      (global.set $next
        (i32.and
          (i32.add (i32.add (global.get $next) (local.get $size)) (i32.const 7))
          (i32.const -8)))
      (local.get $pointer))
    (func (export "evaluate")
      (param i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32)
      (call $set-metadata (i32.const 0) (i32.const KEY_LENGTH) (i32.const 512) (i32.const 15))))
  (core instance $instance (instantiate $processor (with "host" (instance $host))))
  (alias core export $instance "memory" (core memory $memory))
  (alias core export $instance "realloc" (core func $realloc))
  (alias core export $instance "evaluate" (core func $evaluate))
  (type $optional-string (option string))
  (type $argument (tuple string string))
  (type $arguments (list $argument))
  (type $context (record
    (field "method" string)
    (field "namespace" string)
    (field "tool-name" $optional-string)
    (field "arguments" $arguments)
    (field "llm-result" string)
    (field "conversation-id" $optional-string)))
  (export $public-context "evaluation-context" (type $context))
  (func (export "evaluate") (param "ctx" $public-context)
    (canon lift (core func $evaluate) (memory $memory) (realloc $realloc))))
