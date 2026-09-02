use adblock::lists::ParseOptions;
use adblock::request::Request;
use adblock::resources::{PermissionMask, Resource};
use adblock::{Engine, FilterSet};
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr;
use std::sync::OnceLock;

static ENGINE: OnceLock<Engine> = OnceLock::new();

fn build_engine(
    main: Vec<u8>,
    unbreak: Vec<u8>,
    resources: Vec<u8>,
) -> Result<(Engine, usize), String> {
    let main = String::from_utf8(main).map_err(|error| error.to_string())?;
    let unbreak = String::from_utf8(unbreak).map_err(|error| error.to_string())?;
    let count = main
        .lines()
        .chain(unbreak.lines())
        .filter(|line| !line.is_empty() && !line.starts_with('!'))
        .count();
    let options = ParseOptions {
        permissions: PermissionMask::from_bits(1),
        ..ParseOptions::default()
    };
    let mut filters = FilterSet::new(false);
    filters.add_filter_list(main, options);
    filters.add_filter_list(unbreak, options);

    let mut engine = Engine::new_with_filter_set(filters);
    let resources: Vec<Resource> =
        serde_json::from_slice(&resources).map_err(|error| error.to_string())?;
    engine.use_resources(resources);
    Ok((engine, count))
}

fn check(engine: &Engine, url: &str, source: &str, kind: &str, method: &str) -> Option<String> {
    let request = Request::new(url, source, kind, method).ok()?;
    let result = engine.check_network_request(&request);
    if let Some(redirect) = result.redirect {
        return Some(format!("R{redirect}"));
    }
    if result.should_block() {
        return Some("B".to_owned());
    }
    result.rewritten_url.map(|url| format!("R{url}"))
}

fn cosmetics(engine: &Engine, url: &str) -> Result<String, String> {
    serde_json::to_string(&engine.url_cosmetic_resources(url)).map_err(|error| error.to_string())
}

fn hidden_selectors(engine: &Engine, url: &str, query: &str) -> Result<String, String> {
    let query: [Vec<String>; 2] = serde_json::from_str(query).map_err(|error| error.to_string())?;
    let page = engine.url_cosmetic_resources(url);
    let selectors = if page.generichide {
        Vec::new()
    } else {
        engine.hidden_class_id_selectors(&query[0], &query[1], &page.exceptions)
    };
    serde_json::to_string(&selectors).map_err(|error| error.to_string())
}

fn bytes(env: &JNIEnv<'_>, value: JByteArray<'_>) -> Result<Vec<u8>, String> {
    env.convert_byte_array(value)
        .map_err(|error| error.to_string())
}

fn string(env: &mut JNIEnv<'_>, value: JString<'_>) -> Result<String, String> {
    env.get_string(&value)
        .map(String::from)
        .map_err(|error| error.to_string())
}

fn java_string(env: &mut JNIEnv<'_>, value: Result<Option<String>, String>) -> jstring {
    match value {
        Ok(Some(value)) => match env.new_string(value) {
            Ok(value) => value.into_raw(),
            Err(error) => {
                let _ = env.throw_new("java/lang/IllegalStateException", error.to_string());
                ptr::null_mut()
            }
        },
        Ok(None) => ptr::null_mut(),
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            ptr::null_mut()
        }
    }
}

fn safely<T>(operation: impl FnOnce() -> Result<T, String>) -> Result<T, String> {
    catch_unwind(AssertUnwindSafe(operation))
        .unwrap_or_else(|_| Err("native adblock operation panicked".to_owned()))
}

#[no_mangle]
pub extern "system" fn Java_dev_winlandcraft_AdBlockNative_initialize0(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    main: JByteArray<'_>,
    unbreak: JByteArray<'_>,
    resources: JByteArray<'_>,
) -> jint {
    let result = safely(|| {
        let (engine, count) = build_engine(
            bytes(&env, main)?,
            bytes(&env, unbreak)?,
            bytes(&env, resources)?,
        )?;
        ENGINE
            .set(engine)
            .map_err(|_| "adblock engine is already initialized".to_owned())?;
        Ok::<usize, String>(count)
    });
    match result {
        Ok(count) => count.min(jint::MAX as usize) as jint,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_winlandcraft_AdBlockNative_check0(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    url: JString<'_>,
    source: JString<'_>,
    kind: JString<'_>,
    method: JString<'_>,
) -> jstring {
    let result = safely(|| {
        let url = string(&mut env, url)?;
        let source = string(&mut env, source)?;
        let kind = string(&mut env, kind)?;
        let method = string(&mut env, method)?;
        let engine = ENGINE
            .get()
            .ok_or_else(|| "adblock engine is not initialized".to_owned())?;
        Ok(check(engine, &url, &source, &kind, &method))
    });
    java_string(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_dev_winlandcraft_AdBlockNative_cosmetics0(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    url: JString<'_>,
) -> jstring {
    let result = safely(|| {
        let url = string(&mut env, url)?;
        let engine = ENGINE
            .get()
            .ok_or_else(|| "adblock engine is not initialized".to_owned())?;
        cosmetics(engine, &url).map(Some)
    });
    java_string(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_dev_winlandcraft_AdBlockNative_hiddenSelectors0(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    url: JString<'_>,
    query: JString<'_>,
) -> jstring {
    let result = safely(|| {
        let url = string(&mut env, url)?;
        let query = string(&mut env, query)?;
        let engine = ENGINE
            .get()
            .ok_or_else(|| "adblock engine is not initialized".to_owned())?;
        hidden_selectors(engine, &url, &query).map(Some)
    });
    java_string(&mut env, result)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn test_engine() -> Engine {
        build_engine(
            b"||ads.example^\nexample.com##.ad\nexample.com##+js(set-constant, ad, false)".to_vec(),
            b"@@||ads.example/allowed^".to_vec(),
            b"[]".to_vec(),
        )
        .unwrap()
        .0
    }

    #[test]
    fn blocks_and_allows_network_requests() {
        let engine = test_engine();
        assert_eq!(
            check(
                &engine,
                "https://ads.example/banner.js",
                "https://site.example",
                "script",
                "GET"
            ),
            Some("B".to_owned())
        );
        assert_eq!(
            check(
                &engine,
                "https://ads.example/allowed",
                "https://site.example",
                "script",
                "GET"
            ),
            None
        );
    }

    #[test]
    fn returns_cosmetic_selectors() {
        let engine = test_engine();
        let value: serde_json::Value =
            serde_json::from_str(&cosmetics(&engine, "https://example.com").unwrap()).unwrap();
        assert!(value["hide_selectors"]
            .as_array()
            .unwrap()
            .iter()
            .any(|selector| selector == ".ad"));
    }
}
