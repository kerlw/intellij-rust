extern crate config;
extern crate test_runner;

use std::fs::read_dir;
use std::path::Path;

use test_runner::{LLDBConfig, LLDBTestRunner};

fn main() -> Result<(), ()> {
    let mut settings = config::Config::default();
    settings.merge(config::File::with_name("Settings")).unwrap();

    let path = settings.get::<String>("test_dir").unwrap();
    let src_dir = Path::new(&path);
    let src_paths: Vec<_> = read_dir(src_dir)
        .unwrap_or_else(|_| panic!("Tests not found!"))
        .map(|file| file.unwrap().path().as_os_str().to_owned())
        .collect();

    let config = LLDBConfig {
        pretty_printers_path: settings.get::<String>("pretty_printers_path").unwrap(),
        lldb_batchmode: settings.get::<String>("lldb_batchmode").unwrap(),
        lldb_python: settings.get::<String>("lldb_python").unwrap_or_default(),
        lldb_lookup: settings.get::<String>("lldb_lookup").unwrap(),
        with_output: settings.get::<bool>("print_lldb_stdout").unwrap(),
        lldb_native_rust: settings.get::<bool>("lldb_native_rust").unwrap(),
    };

    let mut status = Ok(());

    for path in src_paths {
        let path = Path::new(&path);
        let test_runner = LLDBTestRunner { config: &config, src_path: path };
        let result = test_runner.run();
        let path_string = path.file_name().unwrap().to_str().unwrap();

        match result {
            Ok(_) => {
                println!("{}: passed", path_string);
            }
            Err(e) => {
                println!("{}: failed", path_string);
                println!("{}", e);
                status = Err(());
            }
        }
    }

    status
}
