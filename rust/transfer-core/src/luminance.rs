//! Compensates for gradual lighting changes within a bounded camera frame.

/// Separates dark modules from their local background using a summed-area table.
///
/// The caller has validated the frame dimensions and pixel limit. A window
/// proportional to the short side preserves small modules at either analysis
/// resolution; the 5% bias leaves flat areas white rather than amplifying noise.
pub(super) fn local_threshold(width: usize, height: usize, luminance: &[u8]) -> Vec<u8> {
    let stride = width + 1;
    let mut sums = vec![0_u32; stride * (height + 1)];
    for row in 0..height {
        let mut row_sum = 0_u32;
        for column in 0..width {
            row_sum += u32::from(luminance[row * width + column]);
            sums[(row + 1) * stride + column + 1] = sums[row * stride + column + 1] + row_sum;
        }
    }

    let radius = (width.min(height) / 64).max(8);
    let mut binary = vec![u8::MAX; luminance.len()];
    for row in 0..height {
        let top = row.saturating_sub(radius);
        let bottom = (row + radius + 1).min(height);
        for column in 0..width {
            let left = column.saturating_sub(radius);
            let right = (column + radius + 1).min(width);
            let sum = sums[bottom * stride + right] + sums[top * stride + left]
                - sums[top * stride + right]
                - sums[bottom * stride + left];
            let area = ((bottom - top) * (right - left)) as u64;
            let index = row * width + column;
            if u64::from(luminance[index]) * area * 100 < u64::from(sum) * 95 {
                binary[index] = 0;
            }
        }
    }
    sums.fill(0);
    binary
}
