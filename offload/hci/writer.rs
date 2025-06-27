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

pub trait Write {
    fn write(&self, w: &mut Writer)
    where
        Self: Sized;
}

pub struct Writer {
    vec: Vec<u8>,
}

impl Writer {
    pub(crate) fn new(vec: Vec<u8>) -> Self {
        Self { vec }
    }

    pub(crate) fn into_vec(self) -> Vec<u8> {
        self.vec
    }

    pub(crate) fn put(&mut self, slice: &[u8]) {
        self.vec.extend_from_slice(slice);
    }

    pub(crate) fn write<T: Write>(&mut self, v: &T) {
        v.write(self)
    }

    pub(crate) fn write_u8(&mut self, v: u8) {
        self.write_u32::<1>(v.into());
    }

    pub(crate) fn write_u16(&mut self, v: u16) {
        self.write_u32::<2>(v.into());
    }

    pub(crate) fn write_u32<const N: usize>(&mut self, mut v: u32) {
        for _ in 0..N {
            self.vec.push((v & 0xff) as u8);
            v >>= 8;
        }
    }

    pub(crate) fn write_bytes<const N: usize>(&mut self, bytes: &[u8; N]) {
        self.put(bytes);
    }
}

impl Write for Vec<u8> {
    fn write(&self, w: &mut Writer) {
        w.write_u8(self.len().try_into().unwrap());
        w.put(self);
    }
}

impl Write for Vec<u16> {
    fn write(&self, w: &mut Writer) {
        w.write_u8(self.len().try_into().unwrap());
        for item in self {
            w.write_u16(*item);
        }
    }
}

impl<T: Write> Write for Vec<T> {
    fn write(&self, w: &mut Writer) {
        w.write_u8(self.len().try_into().unwrap());
        for item in self {
            w.write(item);
        }
    }
}

macro_rules! pack {
    ( $( ($x:expr, $n:expr) ),* ) => {
        {
            let mut y = 0;
            let mut _shl = 0;
            $(
                assert!($x & !((1 << $n) - 1) == 0);
                y |= ($x << _shl);
                _shl += $n;
            )*
            y
        }
    };
    ( $x:expr, $n:expr ) => { pack!(($x, $n)) };
}

pub(crate) use pack;
