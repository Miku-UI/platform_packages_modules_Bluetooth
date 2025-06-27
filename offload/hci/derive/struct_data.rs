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

//! Derive of `hci::reader::Read` and `hci::writer::Write` traits on a `struct`
//!
//! ```
//! #[derive(Read, Write)]
//! struct Example {
//!    one_byte: u8,
//!    two_bytes: u16,
//!    #[N(3)] three_bytes: u32,
//!    #[N(4)] four_bytes: u32,
//!    bytes: [u8; 123],
//!    other: OtherType,
//! }
//! ```
//!
//! Produces:
//!
//! ```
//! impl Read for Example {
//!     fn read(r: &mut Reader) -> Option<Self> {
//!         Some(Self {
//!             one_byte: r.read_u8()?,
//!             two_bytes: r.read_u16()?,
//!             three_bytes: r.read_u32::<3>()?,
//!             four_bytes: r.read_u32::<4>()?,
//!             bytes: r.read_bytes()?,
//!             other_type: r.read()?,
//!         })
//!     }
//! }
//!
//! impl Write for Example {
//!     fn write(&self, w: &mut Writer) {
//!         w.write_u8(self.one_byte);
//!         w.write_u16(self.two_bytes);
//!         w.write_u32::<3>(self.three_bytes);
//!         w.write_u32::<4>(self.four_bytes);
//!         w.write_bytes(&self.bytes);
//!         w.write(&self.other_type);
//!     }
//! }
//! ```

use proc_macro2::TokenStream;
use quote::{quote, quote_spanned};
use syn::{spanned::Spanned, Error};

struct Attributes {
    n: Option<usize>,
}

impl Attributes {
    fn parse(syn_attrs: &[syn::Attribute]) -> Result<Attributes, Error> {
        let mut n = None;
        for attr in syn_attrs.iter() {
            match attr {
                attr if attr.path().is_ident("N") => {
                    let lit: syn::LitInt = attr.parse_args()?;
                    n = Some(lit.base10_parse()?);
                }
                attr => return Err(Error::new(attr.span(), "Unrecognized attribute")),
            }
        }
        Ok(Attributes { n })
    }
}

pub(crate) fn derive_read(name: &syn::Ident, data: &syn::DataStruct) -> Result<TokenStream, Error> {
    let mut fields = Vec::new();
    for field in &data.fields {
        let ident = &field.ident.as_ref().unwrap();
        let attrs = Attributes::parse(&field.attrs)?;
        let fn_token = match &field.ty {
            syn::Type::Path(v) if v.path.is_ident("u8") => {
                if attrs.n.unwrap_or(1) != 1 {
                    return Err(Error::new(v.span(), "Expected N(1) for type `u8`"));
                }
                quote_spanned! { v.span() => read_u8()? }
            }
            syn::Type::Path(v) if v.path.is_ident("u16") => {
                if attrs.n.unwrap_or(2) != 2 {
                    return Err(Error::new(v.span(), "Expected N(2) for type `u16`"));
                }
                quote_spanned! { v.span() => read_u16()? }
            }
            syn::Type::Path(v) if v.path.is_ident("u32") => {
                let Some(n) = attrs.n else {
                    return Err(Error::new(v.span(), "`N()` attribute required"));
                };
                if n > 4 {
                    return Err(Error::new(v.span(), "Expected N(n <= 4)"));
                }
                quote_spanned! { v.span() => read_u32::<#n>()? }
            }
            syn::Type::Array(v) => match &*v.elem {
                syn::Type::Path(v) if v.path.is_ident("u8") => {
                    quote_spanned! { v.span() => read_bytes()? }
                }
                _ => return Err(Error::new(v.elem.span(), "Only Byte array supported")),
            },
            ty => quote_spanned! { ty.span() => read()? },
        };
        fields.push(quote! { #ident: r.#fn_token });
    }

    Ok(quote! {
        impl Read for #name {
            fn read(r: &mut Reader) -> Option<Self> {
                Some(Self {
                    #( #fields ),*
                })
            }
        }
    })
}

pub(crate) fn derive_write(
    name: &syn::Ident,
    data: &syn::DataStruct,
) -> Result<TokenStream, Error> {
    let mut fields = Vec::new();
    for field in &data.fields {
        let ident = &field.ident.as_ref().unwrap();
        let attrs = Attributes::parse(&field.attrs)?;
        let fn_token = match &field.ty {
            syn::Type::Path(v) if v.path.is_ident("u8") => {
                if attrs.n.unwrap_or(1) != 1 {
                    return Err(Error::new(v.span(), "Expected N(1) for type `u8`"));
                }
                quote_spanned! { v.span() => write_u8(self.#ident) }
            }
            syn::Type::Path(v) if v.path.is_ident("u16") => {
                if attrs.n.unwrap_or(2) != 2 {
                    return Err(Error::new(v.span(), "Expected N(2) for type `u16`"));
                }
                quote_spanned! { v.span() => write_u16(self.#ident) }
            }
            syn::Type::Path(v) if v.path.is_ident("u32") => {
                let Some(n) = attrs.n else {
                    return Err(Error::new(v.span(), "`N()` attribute required"));
                };
                if n > 4 {
                    return Err(Error::new(v.span(), "Expected N(n <= 4)"));
                }
                quote_spanned! { v.span() => write_u32::<#n>(self.#ident) }
            }
            syn::Type::Array(v) => match &*v.elem {
                syn::Type::Path(v) if v.path.is_ident("u8") => {
                    quote_spanned! { v.span() => write_bytes(&self.#ident) }
                }
                _ => return Err(Error::new(v.elem.span(), "Only Byte array supported")),
            },
            ty => quote_spanned! { ty.span() => write(&self.#ident) },
        };
        fields.push(quote! { w.#fn_token; });
    }

    Ok(quote! {
        impl Write for #name {
            fn write(&self, w: &mut Writer) {
                #( #fields )*
            }
        }
    })
}
