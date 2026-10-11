using Avalonia.Controls;
using LibertyX.Partition.ViewModels;

namespace LibertyX.Partition.Views;

public partial class SpaceView : UserControl
{
    public SpaceView()
    {
        InitializeComponent();
        Map.Drill += n => (DataContext as SpaceViewModel)?.Open(n);
    }
}
