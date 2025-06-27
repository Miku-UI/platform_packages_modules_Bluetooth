// Copyright 2024, The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! Derive of traits :
//! - `hci::reader::Read`, `hci::writer::Write`
//! - `hci::command::CommandToBytes`
//! - `hci::event::EventToBytes`

extern crate proc_macro;
mod enum_data;
mod return_parameters;
mod struct_data;

use proc_macro::TokenStream;
use quote::quote;
use syn::{DeriveInput, Error};

/// Derive of `hci::reader::Read` trait
#[proc_macro_derive(Read, attributes(N))]
pub fn derive_read(input: TokenStream) -> TokenStream {
    let input = syn::parse_macro_input!(input as DeriveInput);
    let (ident, data) = (&input.ident, &input.data);
    let expanded = match (ident.to_string().as_str(), data) {
        ("ReturnParameters", syn::Data::Enum(ref data)) => {
            return_parameters::derive_read(ident, data)
        }
        (_, syn::Data::Enum(ref data)) => enum_data::derive_read(ident, data),
        (_, syn::Data::Struct(ref data)) => struct_data::derive_read(ident, data),
        (_, _) => panic!("Unsupported kind of input"),
    }
    .unwrap_or_else(Error::into_compile_error);
    TokenStream::from(expanded)
}

/// Derive of `hci::reader::Write` trait
#[proc_macro_derive(Write)]
pub fn derive_write(input: TokenStream) -> TokenStream {
    let input = syn::parse_macro_input!(input as DeriveInput);
    let (ident, data) = (&input.ident, &input.data);
    let expanded = match (ident.to_string().as_str(), &data) {
        ("ReturnParameters", syn::Data::Enum(ref data)) => {
            return_parameters::derive_write(ident, data)
        }
        (_, syn::Data::Enum(ref data)) => enum_data::derive_write(ident, data),
        (_, syn::Data::Struct(ref data)) => struct_data::derive_write(ident, data),
        (_, _) => panic!("Unsupported kind of input"),
    }
    .unwrap_or_else(Error::into_compile_error);
    TokenStream::from(expanded)
}

/// Derive of `hci::command::CommandToBytes`
#[proc_macro_derive(CommandToBytes)]
pub fn derive_command_to_bytes(input: TokenStream) -> TokenStream {
    let input = syn::parse_macro_input!(input as DeriveInput);
    let name = &input.ident;
    TokenStream::from(quote! {
        impl CommandToBytes for #name {
            fn to_bytes(&self) -> Vec<u8> {
                Command::to_bytes(self)
            }
        }
    })
}

/// Derive of `hci::command::EventToBytes`
#[proc_macro_derive(EventToBytes)]
pub fn derive_event_to_bytes(input: TokenStream) -> TokenStream {
    let input = syn::parse_macro_input!(input as DeriveInput);
    let name = &input.ident;
    TokenStream::from(quote! {
        impl EventToBytes for #name {
            fn to_bytes(&self) -> Vec<u8> {
                Event::to_bytes(self)
            }
        }
    })
}
