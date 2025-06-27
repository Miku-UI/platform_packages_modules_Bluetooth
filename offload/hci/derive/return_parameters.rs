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

//! Derive of `hci::reader::Read` and `hci::writer::Write` traits on
//! `enum returnReturnParameters`.
//!
//! ```
//! #[derive(Read, Write)]
//! enum ReturnParameters {
//!    CommandOne(CommandOneComplete),
//!    CommandTwo(CommandTwoComplete),
//!    LastIsDefault(OpCode),
//! }
//! ```
//!
//! Produces:
//!
//! ```
//! impl Read for ReturnParameters {
//!     fn read(r: &mut Reader) -> Option<Self> {
//!         Some(match r.read_u16()?.into() {
//!             CommandOne::OPCODE => Self::CommandOne(r.read()?),
//!             CommandTwo::OPCODE => Self::CommandTwo(r.read()?),
//!             opcode => Self::LastIsDefault(opcode),
//!         })
//!     }
//! }
//!
//! impl Write for ReturnParameters {
//!     fn write(&self, w: &mut Writer) {
//!         match self {
//!             Self::CommandOne(p) => {
//!                 w.write(&CommandOne::OPCODE);
//!                 w.write(p);
//!             }
//!             Self::CommandTwo(p) => {
//!                 w.write(&CommandOne::OPCODE);
//!                 w.write(p);
//!             }
//!             Self::LastIsDefault(..) => panic!(),
//!         };
//!     }
//! }
//! ```

use proc_macro2::TokenStream;
use quote::quote;
use syn::Error;

pub(crate) fn derive_read(name: &syn::Ident, data: &syn::DataEnum) -> Result<TokenStream, Error> {
    let mut variants = Vec::new();
    for (i, variant) in data.variants.iter().enumerate() {
        let ident = &variant.ident;

        if i < data.variants.len() - 1 {
            variants.push(quote! {
                #ident::OPCODE => Self::#ident(r.read()?),
            });
        } else {
            variants.push(quote! {
                opcode => Self::#ident(opcode),
            });
        }
    }

    Ok(quote! {
        impl Read for #name {
            fn read(r: &mut Reader) -> Option<Self> {
                Some(match r.read_u16()?.into() {
                    #( #variants )*
                })
            }
        }
    })
}

pub(crate) fn derive_write(name: &syn::Ident, data: &syn::DataEnum) -> Result<TokenStream, Error> {
    let mut variants = Vec::new();
    for (i, variant) in data.variants.iter().enumerate() {
        let ident = &variant.ident;

        if i < data.variants.len() - 1 {
            variants.push(quote! {
                Self::#ident(p) => {
                    w.write(&#ident::OPCODE);
                    w.write(p);
                }
            });
        } else {
            variants.push(quote! {
                Self::#ident(..) => panic!(),
            });
        }
    }

    Ok(quote! {
        impl Write for #name {
            fn write(&self, w: &mut Writer) {
                match self {
                    #( #variants )*
                };
            }
        }
    })
}
