name ECommerce_Mined;

// =====================================================
// Model From Dataset
// =====================================================

init Start;

Start -enter_view-> view : e1

buy -exit-> exit : e2
cart -buy-> buy : e3
cart -exit-> exit : e4
exit -exit-> exit : e5
view -cart-> cart : e6
view -exit-> exit : e7
view -view-> view : e8

// Loop at the final state to avoid deadlock in PRISM
exit -loop-> exit : e9 (1.0000)