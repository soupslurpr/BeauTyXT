use chrono::{Datelike, FixedOffset, Local, TimeZone, Timelike, Utc};
use comemo::Prehashed;
use once_cell::sync::Lazy;
#[cfg(target_family = "unix")]
use seccompiler::{apply_filter_all_threads, BpfProgram, SeccompAction, SeccompFilter};
use std::convert::TryInto;
#[cfg(target_family = "unix")]
use std::os::fd::FromRawFd;
use std::{
    fs::File,
    io::{Read, Seek, Write},
    sync::Mutex,
};
use typst::{foundations::{Dict, Smart}, layout::Abs, model::Document, syntax::package::PackageSpec};
use typst::text::Font;
use typst::text::FontBook;
use typst::Library;
use typst::{diag::EcoString, foundations::Bytes};
use typst::{
    diag::{FileError, FileResult, Severity, Tracepoint},
    eval::Tracer,
    syntax::{FileId, Source, VirtualPath},
    World,
};
use typst::{foundations::Datetime, text::FontInfo};

static MAIN_TYPST_PROJECT_FILE: Lazy<Mutex<Option<TypstProjectFilePathAndFile>>> =
    Lazy::new(|| Mutex::new(None));
static TYPST_PROJECT_FILES: Lazy<Mutex<Vec<TypstProjectFilePathAndFile>>> =
    Lazy::new(|| Mutex::new(vec![]));

static TYPST_WORLD: Lazy<Mutex<Option<SomeTypstWorld>>> = Lazy::new(|| Mutex::new(None));

#[uniffi::export]
pub fn initialize_typst_world() {
    #[cfg(target_family = "unix")]
    let filter: BpfProgram = SeccompFilter::new(
        vec![
            (libc::SYS_getrandom, vec![]),
            (libc::SYS_mprotect, vec![]),
            (222, vec![]), // mmap
            // Not sure why prctl is required
            (libc::SYS_prctl, vec![]),
            (libc::SYS_ioctl, vec![]),
            (libc::SYS_fcntl, vec![]),
            (libc::SYS_getuid, vec![]),
            (libc::SYS_writev, vec![]),
            (libc::SYS_read, vec![]),
            (libc::SYS_write, vec![]),
            (libc::SYS_getsockopt, vec![]),
            (libc::SYS_close, vec![]),
            (80, vec![]), // fstat
            (62, vec![]), // lseek
            (libc::SYS_futex, vec![]),
            (46, vec![]), // ftruncate
            (libc::SYS_clock_gettime, vec![]),
            (libc::SYS_openat, vec![]),
            (79, vec![]), // newfstatat
            (libc::SYS_munmap, vec![]),
        ]
        .into_iter()
        .collect(),
        // mismatch_action
        // SeccompAction::Log, // Useful for testing which syscalls are needed
        SeccompAction::KillProcess,
        // match_action
        SeccompAction::Allow,
        // target architecture of filter
        std::env::consts::ARCH.try_into().unwrap(),
    )
    .unwrap()
    .try_into()
    .unwrap();

    #[cfg(target_family = "unix")]
    apply_filter_all_threads(&filter).unwrap();

    *TYPST_WORLD.lock().unwrap() = Some(SomeTypstWorld::new());

    #[cfg(not(target_family = "unix"))]
    panic!("seccomp is only available on the unix family!")
}

#[derive(uniffi::Record, PartialEq)]
pub struct TypstProjectFilePathAndFd {
    path: String,
    fd: libc::c_int,
}

pub struct TypstProjectFilePathAndFile {
    path: String,
    file: File,
    source: Option<Source>,
    bytes: Option<Bytes>,
}

impl TypstProjectFilePathAndFile {
    #[allow(unreachable_code)]
    pub fn from_project_file_path_and_fd(
        project_file_path_and_fd: &TypstProjectFilePathAndFd,
    ) -> Self {
        TypstProjectFilePathAndFile {
            path: project_file_path_and_fd.path.clone(),
            file: {
                #[cfg(target_family = "unix")]
                {
                    unsafe { std::fs::File::from_raw_fd(project_file_path_and_fd.fd) }
                }
                #[cfg(not(target_family = "unix"))]
                {
                    panic!("Non-unix platforms are currently not supported!")
                }
            },
            source: None,
            bytes: None,
        }
    }
}

#[uniffi::export]
pub fn set_main_typst_project_file(main_project_file_path_and_fd: TypstProjectFilePathAndFd) {
    *MAIN_TYPST_PROJECT_FILE.lock().unwrap() = Some(
        TypstProjectFilePathAndFile::from_project_file_path_and_fd(&main_project_file_path_and_fd),
    );
}

#[uniffi::export]
pub fn add_typst_project_files(new_project_files: Vec<TypstProjectFilePathAndFd>) {
    let new_project_files_paths = new_project_files
        .iter()
        .map(|npf| npf.path.clone())
        .collect::<Vec<String>>();

    // Remove existing files with the same path to avoid duplicate path and file pairs.
    remove_typst_project_files(new_project_files_paths);

    let mut project_files = TYPST_PROJECT_FILES.lock().unwrap();
    project_files.append(
        &mut new_project_files
            .iter()
            .map(|project_file_path_and_fd| {
                TypstProjectFilePathAndFile::from_project_file_path_and_fd(project_file_path_and_fd)
            })
            .collect(),
    );
}

#[uniffi::export]
pub fn remove_typst_project_files(project_files_paths_to_remove: Vec<String>) {
    let mut project_files = TYPST_PROJECT_FILES.lock().unwrap();
    project_files.retain(|pf| !project_files_paths_to_remove.contains(&pf.path))
}

#[uniffi::export]
pub fn clear_typst_project_files() {
    let mut project_files = TYPST_PROJECT_FILES.lock().unwrap();
    project_files.clear();
    project_files.shrink_to_fit();
    *MAIN_TYPST_PROJECT_FILE.lock().unwrap() = None;
}

#[derive(uniffi::Error, Debug, thiserror::Error)]
pub enum CustomFileError {
    #[error("A file was not found at this path.")]
    NotFound { path: String },
    #[error("The file was not valid UTF-8, but should have been.")]
    InvalidUtf8,
    #[error("Another error.\n\nThe optional string can give more details, if available.")]
    Other { details: Option<String> },
}

#[uniffi::export]
pub fn update_typst_project_file(new_text: &str, path: String) -> Result<String, CustomFileError> {
    let world = TYPST_WORLD.lock().unwrap();
    let world = world.as_ref().unwrap();

    if path == "/main.typ" {
        let mut source = world.main();

        let main_project_file = MAIN_TYPST_PROJECT_FILE.lock().unwrap();
        let main_project_file = main_project_file.as_ref().unwrap();

        let mut file = &main_project_file.file;

        source.replace(new_text);

        let text = source.text().to_owned();

        match file.set_len(0) {
            Ok(_) => (),
            Err(err) => {
                return Err(CustomFileError::Other {
                    details: Some(err.to_string()),
                })
            }
        }

        match file.write_all(text.as_bytes()) {
            Ok(_) => (),
            Err(err) => {
                return Err(CustomFileError::Other {
                    details: Some(err.to_string()),
                })
            }
        }

        match file.rewind() {
            Ok(_) => (),
            Err(err) => {
                return Err(CustomFileError::Other {
                    details: Some(format!(
                        "Rewinding file failed. Error: {}, File: {:#?}",
                        err, file
                    )),
                })
            }
        }

        Ok(text)
    } else {
        let mut binding = TYPST_PROJECT_FILES.lock();
        let binding = binding.as_mut();
        let project_files = binding.unwrap();

        match project_files
            .iter_mut()
            .find(|project_file_path_and_file| project_file_path_and_file.path == path)
        {
            Some(project_file_path_and_file) => match &mut project_file_path_and_file.source {
                Some(source) => {
                    source.replace(new_text);
                    let text = source.text().to_owned();

                    let mut file = &project_file_path_and_file.file;

                    match file.set_len(0) {
                        Ok(_) => (),
                        Err(err) => {
                            return Err(CustomFileError::Other {
                                details: Some(err.to_string()),
                            })
                        }
                    }

                    match file.write_all(text.as_bytes()) {
                        Ok(_) => (),
                        Err(err) => {
                            return Err(CustomFileError::Other {
                                details: Some(err.to_string()),
                            })
                        }
                    }

                    match project_file_path_and_file.file.rewind() {
                        Ok(_) => (),
                        Err(err) => {
                            return Err(CustomFileError::Other {
                                details: Some(format!(
                                    "Rewinding file failed. Error: {}, File: {:#?}",
                                    err, project_file_path_and_file.file
                                )),
                            })
                        }
                    }

                    Ok(text)
                }
                None => {
                    let mut buf = vec![];

                    match project_file_path_and_file.file.read_to_end(&mut buf) {
                        Ok(_) => (),
                        Err(err) => {
                            return Err(CustomFileError::Other {
                                details: Some(err.to_string()),
                            })
                        }
                    }

                    match project_file_path_and_file.file.rewind() {
                        Ok(_) => (),
                        Err(err) => {
                            return Err(CustomFileError::Other {
                                details: Some(format!(
                                    "Rewinding file failed. Error: {}, File: {:#?}",
                                    err, project_file_path_and_file.file
                                )),
                            })
                        }
                    }

                    let old_text = match std::string::String::from_utf8(buf) {
                        Ok(text) => text,
                        Err(_) => return Err(CustomFileError::InvalidUtf8),
                    };

                    let mut source =
                        Source::new(FileId::new(None, VirtualPath::new(path)), old_text);

                    source.replace(new_text);

                    project_file_path_and_file.source = Some(source.clone());

                    Ok(source.text().to_owned())
                }
            },
            None => Err(CustomFileError::NotFound { path }),
        }
    }
}

#[uniffi::export]
pub fn get_typst_project_file_text(path: String) -> String {
    let world = TYPST_WORLD.lock().unwrap();
    let world = world.as_ref().unwrap();

    if path == "/main.typ" {
        let source = world.main();

        source.text().to_owned()
    } else {
        let source = world
            .source(FileId::new(None, VirtualPath::new(path)))
            .unwrap(); // TODO: Properly handle errors by returning a result instead of unwrapping

        source.text().to_owned()
    }
}

#[uniffi::export]
pub fn get_typst_pdf() -> Vec<u8> {
    let binding = TYPST_WORLD.lock().unwrap();
    let world = binding.as_ref().unwrap();
    let mut tracer = Tracer::new();

    let document = typst::compile(world, &mut tracer).unwrap();

    typst_pdf::pdf(&document, Smart::Auto, None)
}

/// The severity of a [`SourceDiagnostic`].
#[derive(uniffi::Enum, Debug)]
pub enum TypstCustomSeverity {
    /// A fatal error.
    Error,
    /// A non-fatal warning.
    Warning,
}

/// A part of a diagnostic's [trace](SourceDiagnostic::trace).
#[derive(uniffi::Enum, Debug)]
pub enum TypstCustomTracepoint {
    /// A function call.
    Call {
        /// The spanned value.
        string: Option<String>,
        /// The value's location in source code.
        span: u64,
    },
    /// A show rule application.
    Show {
        /// The spanned value.
        string: String,
        /// The value's location in source code.
        span: u64,
    },
    /// A module import.
    Import {
        /// The value's location in source code.
        span: u64,
    },
}

#[derive(uniffi::Record, Debug)]
pub struct TypstCustomSourceDiagnostic {
    /// Whether the diagnostic is an error or a warning.
    severity: TypstCustomSeverity,
    /// The span of the relevant node in the source code.
    span: u64,
    /// A diagnostic message describing the problem.
    message: String,
    /// The trace of function calls leading to the problem.
    trace: Vec<TypstCustomTracepoint>,
    /// Additional hints to the user, indicating how this problem could be avoided
    /// or worked around.
    hints: Vec<String>,
}

#[derive(uniffi::Error, Debug, thiserror::Error)]
pub enum RenderError {
    #[error("Integer overflow on an operation with")]
    VecCustomSourceDiagnostic {
        custom_source_diagnostics: Vec<TypstCustomSourceDiagnostic>,
    },
}

#[uniffi::export]
pub fn get_typst_svg() -> Result<Vec<u8>, RenderError> {
    let binding = TYPST_WORLD.lock().unwrap();
    let world = binding.as_ref().unwrap();
    let mut tracer = Tracer::new();

    match typst::compile(world, &mut tracer) {
        Ok(document) => {
            Ok(typst_svg::svg_merged(&document, Abs::default()).into_bytes())
        }
        // Convert the errors to our custom errors that can be returned to Kotlin code
        Err(errors) => Err(RenderError::VecCustomSourceDiagnostic {
            custom_source_diagnostics: errors
                .iter()
                .map(|source_diagnostic| TypstCustomSourceDiagnostic {
                    severity: match source_diagnostic.severity {
                        Severity::Error => TypstCustomSeverity::Error,
                        Severity::Warning => TypstCustomSeverity::Warning,
                    },
                    span: source_diagnostic.span.number(),
                    message: source_diagnostic.message.to_string(),
                    trace: source_diagnostic
                        .trace
                        .iter()
                        .map(|spanned| {
                            let span = spanned.span.number();
                            match &spanned.v {
                                Tracepoint::Call(function_call) => TypstCustomTracepoint::Call {
                                    span,
                                    string: function_call
                                        .as_ref()
                                        .map(|eco_string| eco_string.to_string()),
                                },
                                Tracepoint::Show(show_rule_application) => {
                                    TypstCustomTracepoint::Show {
                                        string: show_rule_application.to_string(),
                                        span,
                                    }
                                }
                                Tracepoint::Import => TypstCustomTracepoint::Import { span },
                            }
                        })
                        .collect(),
                    hints: source_diagnostic
                        .hints
                        .iter()
                        .map(|eco_string| eco_string.to_string())
                        .collect(),
                })
                .collect::<Vec<TypstCustomSourceDiagnostic>>(),
        }),
    }
}

pub struct SomeTypstWorld {
    library: Prehashed<Library>,
    fonts: Prehashed<FontBook>,
}

impl SomeTypstWorld {
    pub fn new() -> SomeTypstWorld {
        Self {
            library: Prehashed::new(typst::Library::builder().build()),
            fonts: Prehashed::new(FontBook::from_infos(vec![
                // Noto Serif
                FontInfo::new(include_bytes!("../Noto_Serif/NotoSerif-Regular.ttf"), 0).unwrap(),
                FontInfo::new(include_bytes!("../Noto_Serif/NotoSerif-Bold.ttf"), 0).unwrap(),
                FontInfo::new(include_bytes!("../Noto_Serif/NotoSerif-Italic.ttf"), 0).unwrap(),
                FontInfo::new(include_bytes!("../Noto_Serif/NotoSerif-BoldItalic.ttf"), 0).unwrap(),
                // Roboto
                FontInfo::new(include_bytes!("../Roboto/Roboto-Regular.ttf"), 0).unwrap(),
                FontInfo::new(include_bytes!("../Roboto/Roboto-Bold.ttf"), 0).unwrap(),
                FontInfo::new(include_bytes!("../Roboto/Roboto-Italic.ttf"), 0).unwrap(),
                FontInfo::new(include_bytes!("../Roboto/Roboto-BoldItalic.ttf"), 0).unwrap(),
                // STIX Two Math
                FontInfo::new(include_bytes!("../STIX_Two/STIXTwoMath-Regular.ttf"), 0).unwrap(),
                // STIX Two Text
                FontInfo::new(include_bytes!("../STIX_Two/STIXTwoText-Regular.ttf"), 0).unwrap(),
                FontInfo::new(include_bytes!("../STIX_Two/STIXTwoText-Bold.ttf"), 0).unwrap(),
                FontInfo::new(include_bytes!("../STIX_Two/STIXTwoText-Italic.ttf"), 0).unwrap(),
                FontInfo::new(include_bytes!("../STIX_Two/STIXTwoText-BoldItalic.ttf"), 0).unwrap(),
            ])),
        }
    }
}

impl World for SomeTypstWorld {
    #[doc = " The standard library."]
    fn library(&self) -> &Prehashed<Library>   {
        &self.library
    }

    #[doc = " Metadata about all known fonts."]
    fn book(&self) -> &Prehashed<FontBook>  {
        &self.fonts
    }

    #[doc = " Access the main source file."]
    fn main(&self) -> Source {
        let mut binding = MAIN_TYPST_PROJECT_FILE.lock().unwrap();
        let main_project_file = binding.as_mut().unwrap();

        match &main_project_file.source {
            Some(source) => source.clone(),
            None => {
                let mut buf = vec![];

                main_project_file.file.read_to_end(&mut buf).unwrap();
                main_project_file.file.rewind().unwrap();

                Source::new(
                    FileId::new(None, VirtualPath::new(main_project_file.path.clone())),
                    std::str::from_utf8(&buf).unwrap().to_owned(),
                )
            }
        }
    }

    #[doc = " Try to access the specified source file."]
    #[doc = ""]
    #[doc = " The returned `Source` file\\'s [id](Source::id) does not have to match the"]
    #[doc = " given `id`. Due to symlinks, two different file id\\'s can point to the"]
    #[doc = " same on-disk file. Implementors can deduplicate and return the same"]
    #[doc = " `Source` if they want to, but do not have to."]
    fn source(&self, id: FileId) -> FileResult<Source> {
        let mut binding = TYPST_PROJECT_FILES.lock();
        let binding = binding.as_mut();
        let project_files = binding.unwrap();

        let id_path = id.vpath().as_rooted_path();

        match project_files.iter_mut().find(|project_file_path_and_file| {
            project_file_path_and_file.path == id_path.to_str().unwrap()
        }) {
            Some(project_file_path_and_file) => match &project_file_path_and_file.source {
                Some(source) => FileResult::Ok(source.clone()),
                None => {
                    let mut buf = vec![];

                    match project_file_path_and_file.file.read_to_end(&mut buf) {
                        Ok(_) => (),
                        Err(err) => return FileResult::Err(FileError::from_io(err, id_path)),
                    };

                    match project_file_path_and_file.file.rewind() {
                        Ok(_) => (),
                        Err(err) => {
                            return FileResult::Err(FileError::Other(Some(EcoString::inline(
                                &format!(
                                    "Rewinding file failed. Error: {}, File: {:#?}",
                                    err, project_file_path_and_file.file
                                ),
                            ))))
                        }
                    };

                    match std::string::String::from_utf8(buf) {
                        Ok(text) => FileResult::Ok(Source::new(id, text)),
                        Err(_) => FileResult::Err(FileError::InvalidUtf8),
                    }
                }
            },
            None => FileResult::Err(FileError::NotFound(id_path.into())),
        }
    }

    #[doc = " Try to access the specified file."]
    fn file(&self, id: FileId) -> FileResult<Bytes> {
        let mut binding = TYPST_PROJECT_FILES.lock();
        let binding = binding.as_mut();
        let project_files = binding.unwrap();

        let id_path = id.vpath().as_rooted_path();

        match project_files.iter_mut().find(|project_file_path_and_file| {
            project_file_path_and_file.path == id_path.to_str().unwrap()
        }) {
            Some(project_file_path_and_file) => match &project_file_path_and_file.bytes {
                Some(bytes) => FileResult::Ok(bytes.clone()),
                None => {
                    let mut buf = vec![];

                    match project_file_path_and_file.file.read_to_end(&mut buf) {
                        Ok(_) => (),
                        Err(err) => return FileResult::Err(FileError::from_io(err, id_path)),
                    };

                    match project_file_path_and_file.file.rewind() {
                        Ok(_) => (),
                        Err(err) => {
                            return FileResult::Err(FileError::Other(Some(EcoString::inline(
                                &format!(
                                    "Rewinding file failed. Error: {}, File: {:#?}",
                                    err, project_file_path_and_file.file
                                ),
                            ))))
                        }
                    };

                    Ok(Bytes::from(buf))
                }
            },
            None => FileResult::Err(FileError::NotFound(id_path.into())),
        }
    }

    #[doc = " Try to access the font with the given index in the font book."]
    fn font(&self, index: usize) -> Option<Font> {
        match index {
            // Noto Serif
            0 => Font::new(
                include_bytes!("../Noto_Serif/NotoSerif-Regular.ttf")
                    .as_slice()
                    .into(),
                0,
            ),
            1 => Font::new(
                include_bytes!("../Noto_Serif/NotoSerif-Bold.ttf")
                    .as_slice()
                    .into(),
                0,
            ),
            2 => Font::new(
                include_bytes!("../Noto_Serif/NotoSerif-Italic.ttf")
                    .as_slice()
                    .into(),
                0,
            ),
            3 => Font::new(
                include_bytes!("../Noto_Serif/NotoSerif-BoldItalic.ttf")
                    .as_slice()
                    .into(),
                0,
            ),

            // Roboto
            4 => Font::new(
                include_bytes!("../Roboto/Roboto-Regular.ttf")
                    .as_slice()
                    .into(),
                0,
            ),
            5 => Font::new(
                include_bytes!("../Roboto/Roboto-Bold.ttf")
                    .as_slice()
                    .into(),
                0,
            ),
            6 => Font::new(
                include_bytes!("../Roboto/Roboto-Italic.ttf")
                    .as_slice()
                    .into(),
                0,
            ),
            7 => Font::new(
                include_bytes!("../Roboto/Roboto-BoldItalic.ttf")
                    .as_slice()
                    .into(),
                0,
            ),

            // STIX Two Math
            8 => Font::new(
                include_bytes!("../STIX_Two/STIXTwoMath-Regular.ttf")
                    .as_slice()
                    .into(),
                0,
            ),

            // STIX Two Text
            9 => Font::new(
                include_bytes!("../STIX_Two/STIXTwoText-Regular.ttf")
                    .as_slice()
                    .into(),
                0,
            ),
            10 => Font::new(
                include_bytes!("../STIX_Two/STIXTwoText-Bold.ttf")
                    .as_slice()
                    .into(),
                0,
            ),
            11 => Font::new(
                include_bytes!("../STIX_Two/STIXTwoText-Italic.ttf")
                    .as_slice()
                    .into(),
                0,
            ),
            12 => Font::new(
                include_bytes!("../STIX_Two/STIXTwoText-BoldItalic.ttf")
                    .as_slice()
                    .into(),
                0,
            ),

            _ => panic!("Font index is not valid!"),
        }
    }

    #[doc = " Get the current date."]
    #[doc = ""]
    #[doc = " If no offset is specified, the local date should be chosen. Otherwise,"]
    #[doc = " the UTC date should be chosen with the corresponding offset in hours."]
    #[doc = ""]
    #[doc = " If this function returns `None`, Typst\\'s `datetime` function will"]
    #[doc = " return an error."]
    fn today(&self, offset: Option<i64>) -> Option<Datetime> {
        let hour = 3600;

        match offset {
            Some(offset) => {
                let now = Utc::now();
                let fixed_offset = FixedOffset::east_opt((offset * hour).try_into().ok()?)?;
                let local_time = fixed_offset.from_utc_datetime(&now.naive_utc());

                Some(Datetime::from_ymd_hms(
                    local_time.year(),
                    local_time.month().try_into().ok()?,
                    local_time.day().try_into().ok()?,
                    local_time.hour().try_into().ok()?,
                    local_time.minute().try_into().ok()?,
                    local_time.second().try_into().ok()?,
                )?)
            }
            None => {
                let now = Local::now();

                Some(Datetime::from_ymd_hms(
                    now.year(),
                    now.month().try_into().ok()?,
                    now.day().try_into().ok()?,
                    now.hour().try_into().ok()?,
                    now.minute().try_into().ok()?,
                    now.second().try_into().ok()?,
                )?)
            }
        }
    }

    #[doc = " A list of all available packages and optionally descriptions for them."]
    #[doc = ""]
    #[doc = " This function is optional to implement. It enhances the user experience"]
    #[doc = " by enabling autocompletion for packages. Details about packages from the"]
    #[doc = " `@preview` namespace are available from"]
    #[doc = " `https://packages.typst.org/preview/index.json`."]
    fn packages(&self) -> &[(PackageSpec, Option<EcoString>)] {
        &[]
    }
}
