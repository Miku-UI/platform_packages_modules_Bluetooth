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

//! Derive of `hci::reader::Read` and `hci::writer::Write` traits on an `enum`
//!
//! ```
//! #[derive(Read, Write)]
//! enum Example {
//!    FirstVariant = 0,
//!    SecondVariant = 1,
//! }
//! ```
//!
//! Produces:
//!
//! ```
//! impl Read for Example {
//!     fn read(r: &mut Reader) -> Option<Self> {
//!         match r.read_u8()? {
//!             0 => Some(Self::FirstVariant),
//!             1 => Some(Self::SecondVariant),
//!             _ => None,
//!         }
//!     }
//! }
//!
//! impl Write for Example {
//!     fn write(&self, w: &mut Writer) {
//!         w.write_u8(match self {
//!             Self::FirstVariant => 0,
//!             Self::SecondVariant => 1,
//!         })
//!     }
//! }
//! ```

use proc_macro2::TokenStream;
use quote::quote;
use syn::{spanned::Spanned, Error};

pub(crate) fn derive_read(name: &syn::Ident, data: &syn::DataEnum) -> Result<TokenStream, Error> {
    let mut variants: Vec<TokenStream> = Vec::new();
    for variant in &data.variants {
        let ident = &variant.ident;
        let Some((_, ref discriminant)) = variant.discriminant else {
            return Err(Error::new(variant.span(), "Missing discriminant"));
        };
        variants.push(quote! { #discriminant => Some(Self::#ident) });
    }
    variants.push(quote! { _ => None });

    Ok(quote! {
        impl Read for #name {
            fn read(r: &mut Reader) -> Option<Self> {
                match r.read_u8()? {
                    #( #variants ),*
                }
            }
        }
    })
}

pub(crate) fn derive_write(name: &syn::Ident, data: &syn::DataEnum) -> Result<TokenStream, Error> {
    let mut variants: Vec<TokenStream> = Vec::new();
    for variant in &data.variants {
        let ident = &variant.ident;
        let Some((_, ref discriminant)) = variant.discriminant else {
            return Err(Error::new(variant.span(), "Missing discriminant"));
        };
        variants.push(quote! { Self::#ident => #discriminant });
    }

    Ok(quote! {
        impl Write for #name {
            fn write(&self, w: &mut Writer) {
                w.write_u8(
                    match self {
                        #( #variants ),*
                    }
                )
            }
        }
    })
}
