# References

Collected during planning. Check details against the original before quoting them.

## Books

- Alciatore, D. G. *The Illustrated Principles of Pool and Billiards.* Sterling, 2004.
- Koehler, J. H. *The Science of Pocket Billiards.*
- Byrne, R. *Byrne's New Standard Book of Pool and Billiards.*

## Physics

- Dr. Dave Alciatore's billiards site: technical proofs, videos and FAQs on squirt, swerve, throw, cushions and pockets. <https://billiards.colostate.edu>
- Alciatore, D. G. Technical proofs TP 3.5–3.8 on effective pocket size (slow and fast shots, corner and side pockets, versus approach angle), and a 2013 Billiards Digest column with B. Jewett measuring pocket sizes at soft and firm speed. The jaw tolerances in `PocketPreset` follow their qualitative findings: effective size about the mouth less two inches, side pockets shrinking fast away from straight on, corners favourable along the rail, close to two-to-one tightening with speed at shallow angles. The documents themselves were not read here; check the numbers against them. <https://billiards.colostate.edu/technical_proofs/>
- Alciatore, D. G. Technical proof A.14, "Throw": relative sliding speed at the contact point, the ball–ball friction curve $\mu = a + b\,e^{-c\,v_{\text{rel}}}$ and the throw angle used by `SpinPhysics.computeThrowAnglesRadians`. Related proofs A.17 (effect of friction on throw) and A.24 quote the same fit. <https://billiards.colostate.edu/technical_proofs/new/TP_A-14.pdf> The constants were not re-read from the PDF; check $c$ (1.088 in one proof, 0.77 in another) against the original.
- Leckie, W. and Greenspan, M. "An event-based pool physics simulator." *Advances in Computer Games*, 2006.
- Han, I. "Dynamics in carom and three cushion billiards." *Journal of Mechanical Science and Technology* 19, 2005. The cushion impulse model implemented in `CushionContact` (contact above the ball's centre at the nose height, restitution along the tilted contact normal, Coulomb friction with sticking or sliding decided by the impulse needed to stop the slip). The equations were implemented from the model's physics; check the details against the paper.
- Mathavan, S., Jackson, M. R. and Parkin, R. M. "A theoretical analysis of billiard ball dynamics under cushion impacts." *Proceedings of the Institution of Mechanical Engineers, Part C*, 2010. The fuller cushion model (impact integrated over the compression, friction with the bed), the candidate if the limits of Han's model listed in the physics note matter.
- World Pool-Billiard Association, equipment specifications: cushion nose height 62.5–64.5 % of the ball diameter, the range behind the default `cushionNoseHeightRatio` of 0.635. The same specification gives the pocket cut used by `PocketPreset.TOURNAMENT`: corner mouth 4.5–4.625 in and side mouth 5–5.125 in measured between the cushion noses where they turn into the pocket, facing angles 142° ± 1° (corner) and 104° ± 1° (side), shelf 1–2.25 in (corner) and 0–0.375 in (side). Figures checked against secondary copies of the specification, not the WPA document itself. Check the figure against the current specification.
- pooltool, an open-source billiards simulator in Python by Evan Kiefl. See the repository for its paper citation. <https://github.com/ekiefl/pooltool>

## Computer pool players

- Smith, M. "PickPocket: A computer billiards shark." *Artificial Intelligence*, 2007.
- Archibald, C., Altman, A. and Shoham, Y. "Analysis of a winning computational billiards player." *IJCAI*, 2009.

## Computer vision and on-device ML

- OpenCV. <https://opencv.org>
- YOLOX (Apache-2.0). <https://github.com/Megvii-BaseDetection/YOLOX>
- EfficientDet (Apache-2.0). <https://github.com/google/automl>
- LiteRT, formerly TensorFlow Lite. <https://ai.google.dev/edge/litert>
- CameraX. <https://developer.android.com/media/camera/camerax>
- Roboflow Universe, public datasets (check each licence). <https://universe.roboflow.com>

## Rules

- World Pool-Billiard Association (8-ball, 9-ball, 10-ball). <https://wpapool.com>
- World Professional Billiards and Snooker Association. <https://www.wpbsa.com>
